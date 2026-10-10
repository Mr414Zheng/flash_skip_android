package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.ActionObservationStatus
import com.zhenghuaizhi.flashskip.domain.ports.AbsenceProof
import com.zhenghuaizhi.flashskip.domain.ports.ActionTargetEvidence
import com.zhenghuaizhi.flashskip.domain.ports.TargetObservation
import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.ExecutionTiming
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.RuleAvailability
import com.zhenghuaizhi.flashskip.domain.model.TargetApp
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.model.TextCondition
import com.zhenghuaizhi.flashskip.domain.ports.AutomationStopReason
import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceEvent
import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceResult
import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceSink
import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceStage
import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceTextKind
import com.zhenghuaizhi.flashskip.domain.ports.ClickResult
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.ports.GestureResult
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.PageGateway
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.QueryResult
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalog
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

class AutomationEngine(
    private val catalog: RuleCatalog,
    private val store: FlashSkipStore,
    private val pageGateway: PageGateway,
    private val monotonicClock: MonotonicClock,
    private val wallClock: WallClock,
    private val scope: CoroutineScope,
    private val traceSink: AutomationTraceSink = AutomationTraceSink.None,
) {
    private val mailbox = Channel<Command>(capacity = MAILBOX_CAPACITY)
    private val overflowEpoch = AtomicLong(0)
    private var nextSessionId = 0L
    private var session: Session? = null
    private var foregroundState: ForegroundState = ForegroundState.Unknown
    private var capturePaused = false
    private val lastSuccessByRule = mutableMapOf<String, Long>()
    private val actorJob: Job = scope.launch { consumeCommands() }.also { job ->
        job.invokeOnCompletion { cause ->
            // 包括 actor 尚未启动便被取消的情况，发送方也必须立即结束等待。
            mailbox.cancel(CancellationException("Automation engine stopped", cause))
        }
    }

    fun onForegroundSignal(signal: ForegroundSignal) {
        offer(Command.Foreground(signal))
    }

    fun onContentChanged(identity: PageIdentity, occurredAtElapsedMs: Long) {
        offer(Command.Content(identity, occurredAtElapsedMs))
    }

    /** 等待此前已入队的命令；引擎停止或失败时抛出取消异常。 */
    suspend fun awaitIdle() {
        val barrier = CompletableDeferred<Unit>(actorJob)
        try {
            mailbox.send(Command.Barrier(barrier))
            barrier.await()
        } finally {
            barrier.cancel()
        }
    }

    /** 暂停状态在命令执行后生效；引擎停止或失败时抛出取消异常。 */
    suspend fun setCapturePaused(paused: Boolean) {
        val completion = CompletableDeferred<Unit>(actorJob)
        try {
            mailbox.send(Command.CapturePause(paused, completion))
            completion.await()
        } finally {
            completion.cancel()
        }
    }

    fun close() {
        mailbox.cancel(CancellationException("Automation engine closed"))
        actorJob.cancel()
    }

    private fun offer(command: Command) {
        if (mailbox.trySend(command).isFailure) {
            // 溢出时使所有已排队动作失效；宁可漏关，也不在未知页面上补做点击。
            overflowEpoch.incrementAndGet()
        }
    }

    private suspend fun consumeCommands() {
        try {
            for (command in mailbox) {
                when (command) {
                    is Command.Foreground -> handleForeground(command.signal)
                    is Command.Content -> handleContent(command)
                    is Command.QueryDue -> handleQueryDue(command)
                    is Command.ObserveAction -> handleObserveAction(command)
                    is Command.CapturePause -> handleCapturePause(command)
                    is Command.Barrier -> command.deferred.complete(Unit)
                }
            }
        } finally {
            session?.let { active ->
                withContext(NonCancellable) {
                    withTimeoutOrNull(500) { invalidateWithActionRecord(active, AutomationStopReason.ENGINE_STOPPED) }
                }
            }
            session = null
            foregroundState = ForegroundState.Unknown
        }
    }

    private suspend fun handleForeground(signal: ForegroundSignal) {
        if (capturePaused) return
        when (signal) {
            is ForegroundSignal.TargetActive -> handleTargetActive(signal)
            is ForegroundSignal.OutsideTargets -> {
                session?.let { invalidateWithActionRecord(it, AutomationStopReason.OUTSIDE_TARGETS) }
                session = null
                foregroundState = ForegroundState.OutsideTargets
            }
        }
    }

    private suspend fun handleTargetActive(signal: ForegroundSignal.TargetActive) {
        val targetId = TargetAppId(signal.identity.packageName)
        val rules = catalog.snapshot.value.rulesForPackage(
            signal.identity.packageName,
            signal.identity.appVersion,
        )
        val firstRule = rules.firstOrNull() ?: run {
            traceSkippedForeground(signal, AutomationStopReason.NO_COMPATIBLE_RULES)
            return
        }
        val target = TargetApp(
            id = targetId,
            displayName = firstRule.targetAppName,
            packageName = signal.identity.packageName,
            ruleAvailability = RuleAvailability.Available(rules.size),
        )
        val previous = foregroundState
        val isSameForegroundTarget = previous is ForegroundState.Target &&
            previous.packageName == signal.identity.packageName
        if (isSameForegroundTarget) {
            val active = session?.takeIf { it.target.id == target.id } ?: return
            if (active.terminal) return
            if (signal.identity.appVersion != active.identity.appVersion) {
                invalidateWithActionRecord(active, AutomationStopReason.VERSION_CHANGED)
                session = null
                return
            }
            if (signal.occurredAtElapsedMs >= active.deadlineElapsedMs) {
                // 没有确认离开前台时，同包 Activity 或窗口变化不得重新武装。
                invalidateWithActionRecord(active, AutomationStopReason.SESSION_EXPIRED)
                return
            }
            if (!store.isAppEnabled(target.id)) {
                invalidateWithActionRecord(active, AutomationStopReason.APP_DISABLED)
                return
            }
            if (active.pendingObservation != null) {
                active.identity = signal.identity
                noteObservationEvent(active, signal.occurredAtElapsedMs, windowEvent = true)
                return
            }
            active.identity = signal.identity
            scheduleQuery(active)
            return
        }

        session?.let { invalidateWithActionRecord(it, AutomationStopReason.TARGET_REPLACED) }
        session = null
        foregroundState = ForegroundState.Target(signal.identity.packageName)
        if (!store.isAppEnabled(target.id)) {
            traceSkippedForeground(signal, AutomationStopReason.APP_DISABLED)
            return
        }
        startSession(target, rules, signal.identity, signal.occurredAtElapsedMs)
    }

    private suspend fun handleContent(command: Command.Content) {
        if (capturePaused) return
        val active = session ?: return
        val foreground = foregroundState as? ForegroundState.Target ?: return
        if (foreground.packageName != active.target.packageName) return
        if (active.overflowEpochAtStart != overflowEpoch.get()) {
            invalidateWithActionRecord(active, AutomationStopReason.MAILBOX_OVERFLOW)
            return
        }
        if (active.terminal || command.identity.packageName != active.identity.packageName) return
        if (command.identity.appVersion != active.identity.appVersion) {
            invalidateWithActionRecord(active, AutomationStopReason.VERSION_CHANGED)
            session = null
            return
        }
        if (command.occurredAtElapsedMs >= active.deadlineElapsedMs) {
            invalidateWithActionRecord(active, AutomationStopReason.SESSION_EXPIRED)
            return
        }
        if (!store.isAppEnabled(active.target.id)) {
            invalidateWithActionRecord(active, AutomationStopReason.APP_DISABLED)
            return
        }
        if (active.pendingObservation != null) {
            active.identity = command.identity
            noteObservationEvent(active, command.occurredAtElapsedMs)
            return
        }
        active.identity = command.identity
        scheduleQuery(active)
    }

    private fun startSession(
        target: TargetApp,
        rules: List<AdRule>,
        identity: PageIdentity,
        enteredAt: Long,
    ) {
        session?.queryJob?.cancel()
        session = Session(
            id = ++nextSessionId,
            target = target,
            rules = rules,
            identity = identity,
            enteredAtElapsedMs = enteredAt,
            deadlineElapsedMs = enteredAt + SESSION_WINDOW_MS,
            overflowEpochAtStart = overflowEpoch.get(),
        ).also {
            trace(it, AutomationTraceStage.SESSION_START)
            scheduleQuery(it)
        }
    }

    private fun scheduleQuery(active: Session) {
        if (active.terminal || active.pendingObservation != null) return
        // 连续内容事件只能补充最新页面身份，不能把已计划的最早查询不断向后推迟。
        if (active.queryJob?.isActive == true) return
        val earliestRuleTime = active.rules.minOfOrNull {
            active.enteredAtElapsedMs + it.startupDelayMs
        }
            ?: return
        val dueAt = maxOf(
            monotonicClock.elapsedRealtimeMs() + DEBOUNCE_MS,
            earliestRuleTime,
        )
        scheduleQueryAt(active, dueAt)
    }

    private fun scheduleQueryAt(active: Session, dueAt: Long) {
        if (dueAt >= active.deadlineElapsedMs) return
        active.queryJob?.cancel()
        val generation = ++active.generation
        trace(
            active,
            AutomationTraceStage.QUERY_SCHEDULED,
            delayMs = if (traceSink.enabled) (dueAt - monotonicClock.elapsedRealtimeMs()).coerceAtLeast(0) else null,
        )
        active.queryJob = scope.launch(actorJob) {
            delay((dueAt - monotonicClock.elapsedRealtimeMs()).coerceAtLeast(0))
            mailbox.send(Command.QueryDue(active.id, generation, active.overflowEpochAtStart))
        }
    }

    private suspend fun handleQueryDue(command: Command.QueryDue) {
        if (capturePaused) return
        val active = session ?: return
        if (!active.isCurrent(command) || active.terminal) return
        // QueryDue 已接管该计划；清空引用，避免紧随其后的内容事件误判仍有查询在等待。
        active.queryJob = null
        if (command.overflowEpoch != overflowEpoch.get()) {
            invalidateWithActionRecord(active, AutomationStopReason.MAILBOX_OVERFLOW)
            return
        }
        val now = monotonicClock.elapsedRealtimeMs()
        if (now >= active.deadlineElapsedMs || !store.isAppEnabled(active.target.id)) {
            invalidateWithActionRecord(
                active,
                if (now >= active.deadlineElapsedMs) AutomationStopReason.SESSION_EXPIRED
                else AutomationStopReason.APP_DISABLED,
            )
            return
        }

        val readyRules = active.rules.filter { rule ->
            isRuleReady(rule, active, now)
        }
        var retryAfterTraversalLimit = false
        readyRules.forEach { rule ->
            when (evaluateRule(active, rule, command)) {
                Evaluation.NO_MATCH -> Unit
                Evaluation.RETRY -> retryAfterTraversalLimit = true
                Evaluation.PENDING -> return
                Evaluation.TERMINAL -> return
            }
        }
        val nextStartup = active.rules
            .map { active.enteredAtElapsedMs + it.startupDelayMs }
            .filter { it > now && it < active.deadlineElapsedMs }
            .minOrNull()
        val traversalRetry = if (retryAfterTraversalLimit) {
            monotonicClock.elapsedRealtimeMs() + TRAVERSAL_RETRY_MS
        } else {
            null
        }
        val nextQuery = listOfNotNull(nextStartup, traversalRetry)
            .filter { it < active.deadlineElapsedMs }
            .minOrNull()
        if (nextQuery != null && !active.terminal) {
            scheduleQueryAt(active, nextQuery)
        }
    }

    private fun isRuleReady(rule: AdRule, active: Session, now: Long): Boolean {
        val lastSuccess = lastSuccessByRule[rule.id]
        val skipReason = when {
            now < active.enteredAtElapsedMs + rule.startupDelayMs -> AutomationStopReason.STARTUP_DELAY
            rule.targetAppVersion != null && rule.targetAppVersion != active.identity.appVersion ->
                AutomationStopReason.VERSION_MISMATCH
            active.identity.activityName !in rule.allowedActivities -> AutomationStopReason.ACTIVITY_MISMATCH
            lastSuccess != null && now - lastSuccess < rule.cooldownMs -> AutomationStopReason.COOLDOWN
            else -> null
        }
        if (skipReason != null) trace(active, AutomationTraceStage.RULE_SKIPPED, rule, stopReason = skipReason)
        return skipReason == null
    }

    private suspend fun evaluateRule(
        active: Session,
        rule: AdRule,
        command: Command.QueryDue,
    ): Evaluation {
        var traversalLimited = false
        for (selector in rule.selectors) {
            val queriedAt = monotonicClock.elapsedRealtimeMs()
            if (active.firstQueryAtElapsedMs == null) active.firstQueryAtElapsedMs = queriedAt
            active.selectorQueryCount++
            val result = pageGateway.query(active.identity, selector)
            trace(
                active, AutomationTraceStage.QUERY, rule, selector,
                result = result.traceResult(), durationMs = traceDuration(queriedAt),
            )
            when (result) {
                QueryResult.None -> Unit
                QueryResult.Unique -> {
                    if (active.firstMatchAtElapsedMs == null) {
                        active.firstMatchAtElapsedMs = monotonicClock.elapsedRealtimeMs()
                    }
                    return executeUnique(active, rule, selector, command)
                }
                QueryResult.Ambiguous -> return abandon(active, rule, ActionReason.AMBIGUOUS_MATCH)
                QueryResult.PageChanged -> return abandon(active, rule, ActionReason.PAGE_CHANGED)
                // 单个索引失效后的兜底遍历可能在复杂启动页超限；继续尝试其他稳定选择器，
                // 并在会话期限内短暂重试，不能在广告节点出现前终止整个规则。
                QueryResult.TraversalLimit,
                QueryResult.Unavailable,
                -> traversalLimited = true
            }
        }
        return if (traversalLimited) Evaluation.RETRY else Evaluation.NO_MATCH
    }

    private suspend fun executeUnique(
        active: Session,
        rule: AdRule,
        selector: NodeSelector,
        command: Command.QueryDue,
    ): Evaluation {
        if (!canAct(active, command)) {
            return abandon(active, rule, ActionReason.QUERY_STALE)
        }
        val startedAt = if (traceSink.enabled) monotonicClock.elapsedRealtimeMs() else 0L
        val submission = pageGateway.submitClick(active.identity, selector, deadlineElapsedMs = active.deadlineElapsedMs)
        val result = submission.result
        trace(
            active, AutomationTraceStage.NODE_CLICK, rule, selector,
            result = result.traceResult(), attempt = 1, durationMs = traceDuration(startedAt),
        )
        return when (result) {
            ClickResult.Expired -> abandon(active, rule, ActionReason.QUERY_STALE)
            ClickResult.Clicked -> {
                markActionTime(active, submission.submittedAtElapsedMs)
                beginObservation(active, rule, selector, ActionMethod.NODE_CLICK, submission.evidence, submission.submittedAtElapsedMs)
            }
            // 倒计时节点可能先出现、稍后才可点击；保持会话等待后续内容事件，不做坐标手势。
            ClickResult.NotActionable -> Evaluation.NO_MATCH
            ClickResult.NotSupported -> executeGesture(active, rule, selector, command)
            ClickResult.NodeDisappeared -> abandon(active, rule, ActionReason.NODE_DISAPPEARED)
            ClickResult.Ambiguous -> abandon(active, rule, ActionReason.AMBIGUOUS_MATCH)
            ClickResult.PageChanged -> abandon(active, rule, ActionReason.PAGE_CHANGED)
            ClickResult.TraversalLimit -> abandon(active, rule, ActionReason.TRAVERSAL_LIMIT)
            ClickResult.Unavailable -> abandon(active, rule, ActionReason.NODE_UNAVAILABLE)
        }
    }

    private fun beginObservation(
        active: Session,
        rule: AdRule,
        selector: NodeSelector,
        method: ActionMethod,
        evidence: ActionTargetEvidence?,
        submittedAtElapsedMs: Long?,
    ): Evaluation {
        active.queryJob?.cancel()
        active.generation++
        val now = submittedAtElapsedMs ?: monotonicClock.elapsedRealtimeMs()
        val pending = PendingActionObservation(
            id = ++active.nextObservationId,
            rule = rule,
            selector = selector,
            method = method,
            evidence = evidence,
            acceptedAt = now,
            deadlineAt = minOf(active.deadlineElapsedMs, now + PostActionObserver.WINDOW_MS),
        )
        active.pendingObservation = pending
        trace(active, AutomationTraceStage.ACTION_ACCEPTED, rule, selector,
            result = AutomationTraceResult.ACCEPTED, method = method, attempt = 1)
        scheduleObservation(active, pending, PostActionObserver.FIRST_DELAY_MS)
        return Evaluation.PENDING
    }

    private fun noteObservationEvent(active: Session, occurredAt: Long, windowEvent: Boolean = false) {
        val pending = active.pendingObservation ?: return
        if (occurredAt <= pending.acceptedAt) return
        active.eventRevision++
        active.lastEventAt = maxOf(active.lastEventAt ?: occurredAt, occurredAt)
        if (windowEvent) active.lastWindowEventAt = maxOf(active.lastWindowEventAt ?: occurredAt, occurredAt)
        scheduleObservation(active, pending, PostActionObserver.EVENT_DEBOUNCE_MS)
    }

    private fun scheduleObservation(active: Session, pending: PendingActionObservation, delayMs: Long) {
        val now = monotonicClock.elapsedRealtimeMs()
        val dueAt = minOf(pending.deadlineAt, maxOf(
            now + delayMs, pending.acceptedAt + PostActionObserver.FIRST_DELAY_MS,
            active.lastObservedAt + PostActionObserver.EVENT_DEBOUNCE_MS,
        ))
        if (active.observationJob?.isActive == true && (active.observationDueAt ?: Long.MAX_VALUE) <= dueAt) return
        active.observationJob?.cancel()
        active.observationDueAt = dueAt
        val generation = ++active.observationGeneration
        trace(active, AutomationTraceStage.OBSERVATION_SCHEDULED, pending.rule, pending.selector,
            delayMs = (dueAt - now).coerceAtLeast(0), method = pending.method,
            eventRevision = active.eventRevision)
        active.observationJob = scope.launch(actorJob) {
            delay((dueAt - monotonicClock.elapsedRealtimeMs()).coerceAtLeast(0))
            mailbox.send(Command.ObserveAction(active.id, pending.id, active.overflowEpochAtStart, generation))
        }
    }

    private suspend fun handleObserveAction(command: Command.ObserveAction) {
        val active = session ?: return
        var pending = active.pendingObservation?.takeIf {
            active.id == command.sessionId && it.id == command.observationId &&
                command.generation == active.observationGeneration
        } ?: return
        active.observationJob = null
        active.observationDueAt = null
        if (capturePaused || active.terminal || command.overflowEpoch != overflowEpoch.get() ||
            !store.isAppEnabled(active.target.id)
        ) {
            invalidateWithActionRecord(active, AutomationStopReason.STALE_VERIFICATION)
            return
        }
        val now = monotonicClock.elapsedRealtimeMs()
        if (now >= pending.deadlineAt) {
            finishUnconfirmed(active, pending)
            return
        }
        val fallback = pending.fallbackAttempts < 2 && now - pending.acceptedAt >= PostActionObserver.FALLBACK_AFTER_MS &&
            (pending.lastObservation is TargetObservation.Insufficient ||
                (pending.lastObservation as? TargetObservation.Absent)?.proof in setOf(
                    AbsenceProof.COMPLETE_PAGE, AbsenceProof.ORIGINAL_WINDOW_REMOVED,
                ))
        active.selectorQueryCount++
        val observation = pending.evidence?.let {
            pageGateway.observeAction(active.identity, it, fallback)
        } ?: TargetObservation.Insufficient(ActionReason.NODE_UNAVAILABLE)
        active.lastObservedAt = monotonicClock.elapsedRealtimeMs()
        if (command.overflowEpoch != overflowEpoch.get() || !store.isAppEnabled(active.target.id)) {
            invalidateWithActionRecord(active, AutomationStopReason.STALE_VERIFICATION)
            return
        }
        pending = pending.copy(fallbackAttempts = pending.fallbackAttempts + if (fallback) 1 else 0)
        trace(active, AutomationTraceStage.TARGET_OBSERVATION, pending.rule, pending.selector,
            result = when (observation) {
                is TargetObservation.Present -> AutomationTraceResult.TARGET_PRESENT
                is TargetObservation.Absent -> AutomationTraceResult.TARGET_ABSENT
                is TargetObservation.Insufficient -> AutomationTraceResult.EVIDENCE_INSUFFICIENT
            },
            method = pending.method, verificationIndex = pending.observations + 1,
            proof = (observation as? TargetObservation.Absent)?.proof,
            eventRevision = active.eventRevision, fallback = fallback,
            reason = (observation as? TargetObservation.Insufficient)?.reason)
        when (val decision = PostActionObserver.evaluate(
            pending, observation, active.lastObservedAt, active.eventRevision, active.lastEventAt,
            active.lastWindowEventAt,
        )) {
            is ObservationDecision.Confirmed -> succeed(active, pending.rule, pending.method)
            is ObservationDecision.Unconfirmed -> finishUnconfirmed(active, decision.pending)
            is ObservationDecision.Wait -> {
                var next = decision.pending
                val retry = active.identity == next.evidence?.identity &&
                    ActionRetryPolicy.permits(next, active.lastObservedAt, active.eventRevision)
                trace(active, AutomationTraceStage.RETRY_DECISION, next.rule, next.selector,
                    retryAllowed = retry, attempt = next.attempts, method = next.method,
                    eventRevision = active.eventRevision)
                if (retry) {
                    val submission = pageGateway.submitClick(
                        active.identity, next.selector, next.evidence, deadlineElapsedMs = next.deadlineAt,
                    )
                    trace(active, AutomationTraceStage.VERIFY_CLICK, next.rule, next.selector,
                        result = submission.result.traceResult(), attempt = next.attempts + 1,
                        method = next.method)
                    // 补点后的消失仍由观察流程确认，不把 NodeDisappeared 或 PageChanged 当成功。
                    next = next.copy(attempts = next.attempts + 1, lastObservation = null)
                    if (submission.result == ClickResult.Clicked) next = next.copy(
                        lastActionAt = submission.submittedAtElapsedMs ?: monotonicClock.elapsedRealtimeMs(),
                        stableSince = monotonicClock.elapsedRealtimeMs(),
                        lastObservation = null,
                    )
                }
                active.pendingObservation = next
                scheduleObservation(active, next, PostActionObserver.TIMER_DELAY_MS)
            }
        }
    }

    private suspend fun finishUnconfirmed(active: Session, pending: PendingActionObservation): Evaluation =
        finish(active, pending.rule, ActionOutcome.ABANDONED, pending.method, pending.reason,
            if (pending.lastObservation is TargetObservation.Present) ActionObservationStatus.TARGET_PRESENT
            else ActionObservationStatus.INSUFFICIENT)

    private suspend fun executeGesture(
        active: Session,
        rule: AdRule,
        selector: NodeSelector,
        command: Command.QueryDue,
    ): Evaluation {
        if (!canAct(active, command)) {
            return abandon(active, rule, ActionReason.QUERY_STALE)
        }
        val startedAt = monotonicClock.elapsedRealtimeMs()
        val gestureDeadline = minOf(active.deadlineElapsedMs, startedAt + GESTURE_TIMEOUT_MS)
        val submission = withTimeoutOrNull(gestureDeadline - startedAt) {
            pageGateway.submitGesture(active.identity, selector, gestureDeadline)
        }
        val result = submission?.result ?: GestureResult.TimedOut
        trace(
            active, AutomationTraceStage.GESTURE, rule, selector,
            result = result.traceResult(), durationMs = traceDuration(startedAt),
        )
        return when (result) {
            GestureResult.Accepted,
            GestureResult.Completed -> {
                markActionTime(active, submission?.submittedAtElapsedMs)
                beginObservation(active, rule, selector, ActionMethod.CENTER_GESTURE, submission?.evidence, submission?.submittedAtElapsedMs)
            }
            GestureResult.Rejected -> fail(active, rule, ActionReason.GESTURE_REJECTED)
            GestureResult.Cancelled -> fail(active, rule, ActionReason.GESTURE_CANCELLED)
            GestureResult.TimedOut -> fail(active, rule, ActionReason.GESTURE_TIMED_OUT)
            GestureResult.NodeDisappeared -> abandon(active, rule, ActionReason.NODE_DISAPPEARED)
            GestureResult.Ambiguous -> abandon(active, rule, ActionReason.AMBIGUOUS_MATCH)
            GestureResult.PageChanged -> abandon(active, rule, ActionReason.PAGE_CHANGED)
            GestureResult.TraversalLimit -> abandon(active, rule, ActionReason.TRAVERSAL_LIMIT)
            GestureResult.Unavailable -> abandon(active, rule, ActionReason.NODE_UNAVAILABLE)
        }
    }

    private suspend fun canAct(active: Session, command: Command.QueryDue): Boolean =
        !capturePaused &&
            active.isCurrent(command) &&
            command.overflowEpoch == overflowEpoch.get() &&
            monotonicClock.elapsedRealtimeMs() < active.deadlineElapsedMs &&
            store.isAppEnabled(active.target.id)

    private suspend fun succeed(
        active: Session,
        rule: AdRule,
        method: ActionMethod,
    ): Evaluation {
        lastSuccessByRule[rule.id] = monotonicClock.elapsedRealtimeMs()
        return finish(active, rule, ActionOutcome.CLOSED, method, null, ActionObservationStatus.CONFIRMED)
    }

    private suspend fun abandon(
        active: Session,
        rule: AdRule,
        reason: ActionReason,
    ): Evaluation = finish(active, rule, ActionOutcome.ABANDONED, null, reason)

    private suspend fun fail(
        active: Session,
        rule: AdRule,
        reason: ActionReason,
    ): Evaluation = finish(active, rule, ActionOutcome.FAILED, null, reason)

    private suspend fun finish(
        active: Session,
        rule: AdRule,
        outcome: ActionOutcome,
        method: ActionMethod?,
        reason: ActionReason?,
        observation: ActionObservationStatus? = null,
    ): Evaluation {
        val completedAt = monotonicClock.elapsedRealtimeMs()
        trace(
            active, AutomationTraceStage.FINISHED, rule,
            method = method, outcome = outcome, reason = reason, observation = observation,
        )
        active.terminal = true
        active.queryJob?.cancel()
        active.observationJob?.cancel()
        active.pendingObservation = null
        try {
            store.appendRecord(
                ActionRecord(
                    timestampEpochMs = wallClock.currentTimeMillis(),
                    targetAppId = active.target.id,
                    targetAppName = active.target.displayName,
                    ruleId = rule.id,
                    method = method,
                    outcome = outcome,
                    reason = reason,
                    timing = active.toExecutionTiming(completedAt),
                    actionAccepted = method != null,
                    observation = observation,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            trace(active, AutomationTraceStage.RECORD_WRITE_FAILED, rule, outcome = outcome, reason = reason)
            // 记录写入失败不能重新武装已结束的动作；协程取消仍由上层正常处理。
        }
        return Evaluation.TERMINAL
    }

    private suspend fun invalidateWithActionRecord(active: Session, reason: AutomationStopReason) {
        val pending = active.pendingObservation?.takeIf { !active.terminal }
        invalidate(active, reason)
        // 每个已接受动作都保留未确认记录，页面边界和观察超时不能抹掉动作事实。
        if (pending != null) finishUnconfirmed(active, pending.copy(
            lastObservation = TargetObservation.Insufficient(
                if (reason == AutomationStopReason.OUTSIDE_TARGETS || reason == AutomationStopReason.TARGET_REPLACED)
                    ActionReason.PAGE_CHANGED else pending.reason,
            ),
        ))
    }

    private fun invalidate(active: Session, reason: AutomationStopReason) {
        if (!active.terminal) trace(active, AutomationTraceStage.INVALIDATED, stopReason = reason)
        active.queryJob?.cancel()
        active.observationJob?.cancel()
        active.pendingObservation = null
        active.terminal = true
        active.generation++
    }

    private suspend fun handleCapturePause(command: Command.CapturePause) {
        if (command.paused != capturePaused) {
            session?.let { invalidateWithActionRecord(it, AutomationStopReason.CAPTURE_PAUSED) }
            session = null
            foregroundState = ForegroundState.Unknown
            capturePaused = command.paused
        }
        command.completion.complete(Unit)
    }

    private fun traceSkippedForeground(signal: ForegroundSignal.TargetActive, reason: AutomationStopReason) {
        if (!traceSink.enabled) return
        runCatching {
            traceSink.record(AutomationTraceEvent(
                sessionId = 0,
                elapsedMs = 0,
                stage = AutomationTraceStage.FOREGROUND_SKIPPED,
                targetHash = signal.identity.packageName.hashCode(),
                windowId = signal.identity.windowId,
                stopReason = reason,
            ))
        }
    }

    private fun traceDuration(startedAt: Long): Long? = if (traceSink.enabled) {
        (monotonicClock.elapsedRealtimeMs() - startedAt).coerceAtLeast(0)
    } else null

    private fun trace(
        active: Session,
        stage: AutomationTraceStage,
        rule: AdRule? = null,
        selector: NodeSelector? = null,
        result: AutomationTraceResult? = null,
        attempt: Int? = null,
        durationMs: Long? = null,
        delayMs: Long? = null,
        method: ActionMethod? = null,
        outcome: ActionOutcome? = null,
        reason: ActionReason? = null,
        stopReason: AutomationStopReason? = null,
        verificationIndex: Int? = null,
        queryOnly: Boolean? = null,
        observation: ActionObservationStatus? = null,
        proof: AbsenceProof? = null,
        eventRevision: Long? = null,
        fallback: Boolean? = null,
        retryAllowed: Boolean? = null,
    ) {
        if (!traceSink.enabled) return
        val event = AutomationTraceEvent(
            sessionId = active.id,
            elapsedMs = (monotonicClock.elapsedRealtimeMs() - active.enteredAtElapsedMs).coerceAtLeast(0),
            stage = stage,
            targetHash = active.target.packageName.hashCode(),
            windowId = active.identity.windowId,
            ruleHash = rule?.id?.hashCode(),
            selectorIndex = selector?.let { rule?.selectors?.indexOf(it) },
            selectorHash = selector?.hashCode(),
            resourceIdHash = selector?.resourceId?.hashCode(),
            ancestorIdHash = selector?.ancestorResourceId?.hashCode(),
            textKind = when (selector?.text) {
                is TextCondition.Exact -> AutomationTraceTextKind.EXACT
                is TextCondition.Prefix -> AutomationTraceTextKind.PREFIX
                is TextCondition.SkipCountdown -> AutomationTraceTextKind.SKIP_COUNTDOWN
                TextCondition.NumericCountdown -> AutomationTraceTextKind.NUMERIC_COUNTDOWN
                null -> null
            },
            hasDescription = selector?.let { it.contentDescription != null },
            ancestorDistance = selector?.ancestorDistance,
            actionAncestorDistance = selector?.let { it.actionAncestorDistance ?: 0 },
            result = result,
            attempt = attempt,
            verificationIndex = verificationIndex,
            queryOnly = queryOnly,
            durationMs = durationMs,
            delayMs = delayMs,
            queryCount = active.selectorQueryCount,
            ruleCount = active.rules.size,
            method = method,
            outcome = outcome,
            reason = reason,
            stopReason = stopReason,
            observation = observation,
            proof = proof,
            eventRevision = eventRevision,
            fallback = fallback,
            retryAllowed = retryAllowed,
        )
        // 诊断接收者异常不能改变执行结果或使 actor 停止。
        runCatching { traceSink.record(event) }
    }

    private data class Session(
        val id: Long,
        val target: TargetApp,
        val rules: List<AdRule>,
        var identity: PageIdentity,
        val enteredAtElapsedMs: Long,
        val deadlineElapsedMs: Long,
        val overflowEpochAtStart: Long,
        var terminal: Boolean = false,
        var generation: Long = 0,
        var queryJob: Job? = null,
        var nextObservationId: Long = 0,
        var pendingObservation: PendingActionObservation? = null,
        var observationDueAt: Long? = null,
        var observationGeneration: Long = 0,
        var eventRevision: Long = 0,
        var lastEventAt: Long? = null,
        var lastWindowEventAt: Long? = null,
        var lastObservedAt: Long = 0,
        var observationJob: Job? = null,
        var firstQueryAtElapsedMs: Long? = null,
        var firstMatchAtElapsedMs: Long? = null,
        var actionAtElapsedMs: Long? = null,
        var selectorQueryCount: Int = 0,
    ) {
        fun isCurrent(command: Command.QueryDue): Boolean =
            id == command.sessionId && generation == command.generation

        fun toExecutionTiming(completedAtElapsedMs: Long): ExecutionTiming = ExecutionTiming(
            targetToFirstQueryMs = elapsedSinceTarget(firstQueryAtElapsedMs),
            targetToFirstMatchMs = elapsedSinceTarget(firstMatchAtElapsedMs),
            targetToActionMs = elapsedSinceTarget(actionAtElapsedMs),
            targetToCompletedMs = (completedAtElapsedMs - enteredAtElapsedMs).coerceAtLeast(0),
            selectorQueryCount = selectorQueryCount,
        )

        private fun elapsedSinceTarget(timestamp: Long?): Long? =
            timestamp?.let { (it - enteredAtElapsedMs).coerceAtLeast(0) }
    }

    private fun markActionTime(active: Session, submittedAtElapsedMs: Long? = null) {
        if (active.actionAtElapsedMs == null) {
            active.actionAtElapsedMs = submittedAtElapsedMs ?: monotonicClock.elapsedRealtimeMs()
        }
    }

    private sealed interface Command {
        data class Foreground(val signal: ForegroundSignal) : Command
        data class Content(val identity: PageIdentity, val occurredAtElapsedMs: Long) : Command
        data class QueryDue(
            val sessionId: Long,
            val generation: Long,
            val overflowEpoch: Long,
        ) : Command
        data class ObserveAction(
            val sessionId: Long,
            val observationId: Long,
            val overflowEpoch: Long,
            val generation: Long,
        ) : Command
        data class CapturePause(
            val paused: Boolean,
            val completion: CompletableDeferred<Unit>,
        ) : Command
        data class Barrier(val deferred: CompletableDeferred<Unit>) : Command
    }

    private sealed interface ForegroundState {
        data object Unknown : ForegroundState
        data object OutsideTargets : ForegroundState
        data class Target(val packageName: String) : ForegroundState
    }

    private enum class Evaluation { NO_MATCH, RETRY, PENDING, TERMINAL }

    private companion object {
        const val SESSION_WINDOW_MS = 10_000L
        const val DEBOUNCE_MS = 120L
        const val TRAVERSAL_RETRY_MS = 250L
        const val MAILBOX_CAPACITY = 64
        const val GESTURE_TIMEOUT_MS = 1_000L
    }
}
