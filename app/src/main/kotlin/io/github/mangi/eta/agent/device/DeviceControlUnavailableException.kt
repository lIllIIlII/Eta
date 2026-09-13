package io.github.mangi.eta.agent.device

internal class DeviceControlUnavailableException : IllegalStateException(
    "Eta 无障碍服务已断开，请重新开启服务并观察屏幕",
)
