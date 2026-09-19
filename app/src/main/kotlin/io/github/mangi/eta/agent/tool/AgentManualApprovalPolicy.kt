package io.github.mangi.eta.agent.tool

internal object AgentManualApprovalPolicy {

    val DEVICE_CONTROL_TOOL_NAMES: Set<String> = setOf(
        "launch_app", "open_uri", "open_system_panel", "press_key",
        "set_alarm", "set_timer", "set_volume", "media_control",
        "set_setting", "set_device_state", "app_state_control",
        "tap", "tap_area", "tap_element", "long_press", "long_press_element",
        "swipe", "scroll", "scroll_element", "input_text", "replace_text",
        "clear_text", "paste_text", "set_clipboard", "wait_for_text",
        "wait_for_package",
    )

    fun isDeviceControlTool(toolName: String): Boolean = toolName in DEVICE_CONTROL_TOOL_NAMES

    fun requiresManualApproval(toolName: String, manualControlEnabled: Boolean): Boolean =
        manualControlEnabled && isDeviceControlTool(toolName)

    fun denialMessage(toolName: String): String =
        "用户已开启“AI 操控手机需手动允许”，本次 $toolName 操作没有被手动允许，因此未执行。" +
            "请勿在当前任务中重复调用该工具，可以先向用户说明目的，等用户允许后再试。"
}
