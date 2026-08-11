package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.TargetApp
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.ClickResult
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.ports.GestureResult
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.PageGateway
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.QueryResult
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalog
import com.zhenghuaizhi.flashskip.domain.rules.TargetAppRegistry
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AutomationEngine(
    private val catalog: RuleCatalog,
    private val store: FlashSkipStore,
    private val pageGateway: PageGateway,
    private val monotonicClock: MonotonicClock,
    private val wallClock: WallClock,
    private val scope: CoroutineScope,
) {
    private val mailbox = Channel<Command>(capacity = MAILBOX_CAPACITY)
    private val overflowEpoch = AtomicLong(0)
    private val actorJob = scope.launch { consumeCommands() }
    private var nextSessionId = 0L
    private var session: Session? = null
    private var foregroundState: ForegroundState = ForegroundState.Unknown
    private var capturePaused = false
    private val lastSuccessByRule = mutableMapOf<String, Long>()
    private val nodeClickVerifier = NodeClickVerifier(pageGateway)

    fun onForegroundSignal(signal: ForegroundSignal) {
        offer(Command.Foreground(signal))
    }

    fun onContentChanged(identity: PageIdentity, occurredAtElapsedMs: Long) {
        offer(Command.Content(identity, occurredAtElapsedMs))
    }

    suspend fun awaitIdle() {
        val barrier = CompletableDeferred<Unit>()
        mailbox.send(Command.Barrier(barrier))
        barrier.await()
    }

    suspend fun setCapturePaused(paused: Boolean) {
        val completion = CompletableDeferred<Unit>()
        mailbox.send(Command.CapturePause(paused, completion))
        completion.await()
    }

    fun close() {
        actorJob.cancel()
        session?.let(::invalidate)
        session = null
        foregroundState = ForegroundState.Unknown
        mailbox.close()
    }

    private fun offer(command: Command) {
        if (mailbox.trySend(command).isFailure) {
            // 溢出时使所有已排队动作失效；宁可漏关，也不在未知页面上补做点击。
            overflowEpoch.incrementAndGet()
        }
    }

    private suspend fun consumeCommands() {
        for (command in mailbox) {
            when (command) {
                is Command.Foreground -> handleForeground(command.signal)
                is Command.Content -> handleContent(command)
                is Command.QueryDue -> handleQueryDue(command)
                is Command.VerifyClick -> handleVerifyClick(command)
                is Command.CapturePause -> handleCapturePause(command)
                is Command.Barrier -> command.deferred.complete(Unit)
            }
        }
    }

    private suspend fun handleForeground(signal: ForegroundSignal) {
        if (capturePaused) return
        when (signal) {
            is ForegroundSignal.TargetActive -> handleTargetActive(signal)
            is ForegroundSignal.OutsideTargets -> {
                session?.let(::invalidate)
                session = null
                foregroundState = ForegroundState.OutsideTargets
            }
        }
    }

    private suspend fun handleTargetActive(signal: ForegroundSignal.TargetActive) {
        val target = TargetAppRegistry.targetForPackage(signal.identity.packageName) ?: return
        val previous = foregroundState
        val isSameForegroundTarget = previous is ForegroundState.Target &&
            previous.targetId == target.id
        if (isSameForegroundTarget) {
            val active = session?.takeIf { it.target.id == target.id } ?: return
            if (active.terminal) return
            if (signal.identity.appVersion != active.identity.appVersion) {
                invalidate(active)
                session = null
                return
            }
            if (signal.occurredAtElapsedMs >= active.deadlineElapsedMs) {
                // 没有确认离开前台时，同包 Activity 或窗口变化不得重新武装。
                invalidate(active)
                return
            }
            if (!store.isAppEnabled(target.id)) {
                invalidate(active)
                return
            }
            if (active.pendingClick != null) return
            active.identity = signal.identity
            scheduleQuery(active)
            return
        }

        session?.let(::invalidate)
        session = null
        foregroundState = ForegroundState.Target(target.id)
        if (!store.isAppEnabled(target.id)) return
        val rules = catalog.snapshot.value.rulesForTarget(target.id, signal.identity.appVersion)
        if (rules.isEmpty()) return
        startSession(target, rules, signal.identity, signal.occurredAtElapsedMs)
    }

    private suspend fun handleContent(command: Command.Content) {
        if (capturePaused) return
        val active = session ?: return
        val foreground = foregroundState as? ForegroundState.Target ?: return
        if (foreground.targetId != active.target.id) return
        if (active.overflowEpochAtStart != overflowEpoch.get()) {
            invalidate(active)
            return
        }
        if (active.terminal || command.identity.packageName != active.identity.packageName) return
        if (command.identity.appVersion != active.identity.appVersion) {
            invalidate(active)
            session = null
            return
        }
        if (command.occurredAtElapsedMs >= active.deadlineElapsedMs) {
            invalidate(active)
            return
        }
        if (!store.isAppEnabled(active.target.id)) {
            invalidate(active)
            return
        }
        if (active.pendingClick != null) return
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
        ).also(::scheduleQuery)
    }

    private fun scheduleQuery(active: Session) {
        if (active.terminal || active.pendingClick != null) return
        active.queryJob?.cancel()
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
        active.queryJob = scope.launch {
            delay((dueAt - monotonicClock.elapsedRealtimeMs()).coerceAtLeast(0))
            mailbox.send(Command.QueryDue(active.id, generation, active.overflowEpochAtStart))
        }
    }

    private suspend fun handleQueryDue(command: Command.QueryDue) {
        if (capturePaused) return
        val active = session ?: return
        if (!active.isCurrent(command) || active.terminal) return
        if (command.overflowEpoch != overflowEpoch.get()) {
            invalidate(active)
            return
        }
        val now = monotonicClock.elapsedRealtimeMs()
        if (now >= active.deadlineElapsedMs || !store.isAppEnabled(active.target.id)) {
            invalidate(active)
            return
        }

        val readyRules = active.rules.filter { rule ->
            isRuleReady(rule, active, now)
        }
        readyRules.forEach { rule ->
            when (evaluateRule(active, rule, command)) {
                Evaluation.NO_MATCH -> Unit
                Evaluation.PENDING -> return
                Evaluation.TERMINAL -> return
            }
        }
        val nextStartup = active.rules
            .map { active.enteredAtElapsedMs + it.startupDelayMs }
            .filter { it > now && it < active.deadlineElapsedMs }
            .minOrNull()
        if (nextStartup != null && !active.terminal) {
            scheduleQueryAt(active, nextStartup)
        }
    }

    private fun isRuleReady(rule: AdRule, active: Session, now: Long): Boolean {
        if (now < active.enteredAtElapsedMs + rule.startupDelayMs) return false
        if (rule.targetAppVersion != null && rule.targetAppVersion != active.identity.appVersion) {
            return false
        }
        if (active.identity.activityName !in rule.allowedActivities) return false
        val lastSuccess = lastSuccessByRule[rule.id] ?: return true
        return now - lastSuccess >= rule.cooldownMs
    }

    private suspend fun evaluateRule(
        active: Session,
        rule: AdRule,
        command: Command.QueryDue,
    ): Evaluation {
        for (selector in rule.selectors) {
            when (pageGateway.query(active.identity, selector)) {
                QueryResult.None -> Unit
                QueryResult.Unique -> return executeUnique(active, rule, selector, command)
                QueryResult.Ambiguous -> return abandon(active, rule, ActionReason.AMBIGUOUS_MATCH)
                QueryResult.PageChanged -> return abandon(active, rule, ActionReason.PAGE_CHANGED)
                QueryResult.TraversalLimit -> return abandon(active, rule, ActionReason.TRAVERSAL_LIMIT)
            }
        }
        return Evaluation.NO_MATCH
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
        return when (pageGateway.click(active.identity, selector)) {
            ClickResult.Clicked -> beginClickVerification(active, rule, selector)
            ClickResult.NotSupported -> executeGesture(active, rule, selector, command)
            ClickResult.NodeDisappeared -> abandon(active, rule, ActionReason.NODE_DISAPPEARED)
            ClickResult.Ambiguous -> abandon(active, rule, ActionReason.AMBIGUOUS_MATCH)
            ClickResult.PageChanged -> abandon(active, rule, ActionReason.PAGE_CHANGED)
            ClickResult.TraversalLimit -> abandon(active, rule, ActionReason.TRAVERSAL_LIMIT)
        }
    }

    private fun beginClickVerification(
        active: Session,
        rule: AdRule,
        selector: NodeSelector,
    ): Evaluation {
        active.queryJob?.cancel()
        active.generation++
        val pending = PendingNodeClick(
            id = ++active.nextPendingClickId,
            rule = rule,
            selector = selector,
            attempts = 1,
        )
        active.pendingClick = pending
        scheduleClickVerification(active, pending)
        return Evaluation.PENDING
    }

    private fun scheduleClickVerification(active: Session, pending: PendingNodeClick) {
        active.clickVerificationJob?.cancel()
        active.clickVerificationJob = scope.launch {
            delay(NodeClickVerifier.VERIFY_DELAY_MS)
            mailbox.send(
                Command.VerifyClick(
                    sessionId = active.id,
                    pendingClickId = pending.id,
                    overflowEpoch = active.overflowEpochAtStart,
                ),
            )
        }
    }

    private suspend fun handleVerifyClick(command: Command.VerifyClick) {
        val active = session ?: return
        val pending = active.pendingClick?.takeIf {
            active.id == command.sessionId && it.id == command.pendingClickId
        } ?: return
        if (!canContinueClickVerification(active, command)) {
            invalidate(active)
            return
        }
        when (val result = nodeClickVerifier.verify(active.identity, pending)) {
            NodeClickVerification.Confirmed ->
                succeed(active, pending.rule, ActionMethod.NODE_CLICK)

            is NodeClickVerification.Retry -> {
                active.pendingClick = result.pending
                scheduleClickVerification(active, result.pending)
            }

            is NodeClickVerification.Abandoned ->
                abandon(active, pending.rule, result.reason)

            is NodeClickVerification.Failed -> fail(active, pending.rule, result.reason)
        }
    }

    private suspend fun canContinueClickVerification(
        active: Session,
        command: Command.VerifyClick,
    ): Boolean =
        !capturePaused &&
            !active.terminal &&
            command.overflowEpoch == overflowEpoch.get() &&
            monotonicClock.elapsedRealtimeMs() < active.deadlineElapsedMs &&
            store.isAppEnabled(active.target.id)

    private suspend fun executeGesture(
        active: Session,
        rule: AdRule,
        selector: NodeSelector,
        command: Command.QueryDue,
    ): Evaluation {
        if (!canAct(active, command)) {
            return abandon(active, rule, ActionReason.QUERY_STALE)
        }
        return when (pageGateway.gestureAtCenter(active.identity, selector)) {
            GestureResult.Completed -> succeed(active, rule, ActionMethod.CENTER_GESTURE)
            GestureResult.Rejected -> fail(active, rule, ActionReason.GESTURE_REJECTED)
            GestureResult.Cancelled -> fail(active, rule, ActionReason.GESTURE_CANCELLED)
            GestureResult.NodeDisappeared -> abandon(active, rule, ActionReason.NODE_DISAPPEARED)
            GestureResult.Ambiguous -> abandon(active, rule, ActionReason.AMBIGUOUS_MATCH)
            GestureResult.PageChanged -> abandon(active, rule, ActionReason.PAGE_CHANGED)
            GestureResult.TraversalLimit -> abandon(active, rule, ActionReason.TRAVERSAL_LIMIT)
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
        return finish(active, rule, ActionOutcome.CLOSED, method, null)
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
    ): Evaluation {
        active.terminal = true
        active.queryJob?.cancel()
        active.clickVerificationJob?.cancel()
        active.pendingClick = null
        runCatching {
            store.appendRecord(
                ActionRecord(
                    timestampEpochMs = wallClock.currentTimeMillis(),
                    targetAppId = active.target.id,
                    ruleId = rule.id,
                    method = method,
                    outcome = outcome,
                    reason = reason,
                ),
            )
        }
        return Evaluation.TERMINAL
    }

    private fun invalidate(active: Session) {
        active.queryJob?.cancel()
        active.clickVerificationJob?.cancel()
        active.pendingClick = null
        active.terminal = true
        active.generation++
    }

    private fun handleCapturePause(command: Command.CapturePause) {
        if (command.paused != capturePaused) {
            session?.let(::invalidate)
            session = null
            foregroundState = ForegroundState.Unknown
            capturePaused = command.paused
        }
        command.completion.complete(Unit)
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
        var nextPendingClickId: Long = 0,
        var pendingClick: PendingNodeClick? = null,
        var clickVerificationJob: Job? = null,
    ) {
        fun isCurrent(command: Command.QueryDue): Boolean =
            id == command.sessionId && generation == command.generation
    }

    private sealed interface Command {
        data class Foreground(val signal: ForegroundSignal) : Command
        data class Content(val identity: PageIdentity, val occurredAtElapsedMs: Long) : Command
        data class QueryDue(
            val sessionId: Long,
            val generation: Long,
            val overflowEpoch: Long,
        ) : Command
        data class VerifyClick(
            val sessionId: Long,
            val pendingClickId: Long,
            val overflowEpoch: Long,
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
        data class Target(val targetId: TargetAppId) : ForegroundState
    }

    private enum class Evaluation { NO_MATCH, PENDING, TERMINAL }

    private companion object {
        const val SESSION_WINDOW_MS = 10_000L
        const val DEBOUNCE_MS = 120L
        const val MAILBOX_CAPACITY = 64
    }
}
