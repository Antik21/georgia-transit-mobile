package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.cValue
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIAccessibilityIdentificationProtocol
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIImage
import platform.UIKit.UIView
import platform.UIKit.setAccessibilityLabel
import platform.objc.sel_registerName

/** Uses the platform SF Symbol for location and preserves the native accessibility identifier. */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformMyLocationButton(
    contentDescription: String,
    automationId: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val currentOnClick = rememberUpdatedState(onClick)
    UIKitView(
        factory = { IosMyLocationControl() },
        modifier = modifier,
        update = { control ->
            control.update(
                contentDescription = contentDescription,
                automationId = automationId,
                enabled = enabled,
                onClick = currentOnClick.value,
            )
        },
        properties = UIKitInteropProperties(
            interactionMode = UIKitInteropInteractionMode.NonCooperative,
            isNativeAccessibilityEnabled = true,
        ),
    )
}

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
private class IosMyLocationControl : UIView(frame = cValue()) {
    private val button = UIButton.buttonWithType(UIButtonTypeSystem)
    private var onClick: () -> Unit = {}

    init {
        button.setImage(UIImage.systemImageNamed("location.fill"), forState = UIControlStateNormal)
        button.addTarget(this, sel_registerName("locationTapped"), UIControlEventTouchUpInside)
        addSubview(button)
    }

    fun update(
        contentDescription: String,
        automationId: String,
        enabled: Boolean,
        onClick: () -> Unit,
    ) {
        button.setAccessibilityLabel(contentDescription)
        (button as? UIAccessibilityIdentificationProtocol)?.setAccessibilityIdentifier(automationId)
        button.setEnabled(enabled)
        this.onClick = onClick
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        button.setFrame(bounds)
    }

    @ObjCAction
    fun locationTapped() {
        onClick()
    }
}
