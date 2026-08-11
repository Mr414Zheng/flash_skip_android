package com.zhenghuaizhi.flashskip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.zhenghuaizhi.flashskip.platform.settings.accessibilitySettingsIntent
import com.zhenghuaizhi.flashskip.ui.home.FlashSkipRoute
import com.zhenghuaizhi.flashskip.ui.home.HomeViewModel
import com.zhenghuaizhi.flashskip.ui.theme.FlashSkipTheme

class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as FlashSkipApplication).container

    private val viewModel by viewModels<HomeViewModel> { container.homeViewModelFactory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FlashSkipTheme {
                FlashSkipRoute(
                    viewModel = viewModel,
                    onOpenAccessibilitySettings = {
                        startActivity(accessibilitySettingsIntent())
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshServiceStatus()
    }
}
