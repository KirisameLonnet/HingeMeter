# HingleMeter

只是一个最最简单的安卓折叠手机铰链角度指示器，仿照Apple水平仪UI

# 使用说明

- 进入应用后会显示当前铰链角度（需设备支持 `TYPE_HINGE_ANGLE` 传感器）。
- 双击画面可选择并插入 GIF 贴图。
- 贴图可拖动位置；双指可缩放与旋转。
- 多个贴图时，最后编辑的贴图位于最上层。
- 长按画面中心可进入视频放映模式并选择视频；视频会居中裁剪填充屏幕，不拉伸、不留黑边。
- 视频放映模式下会隐藏角度数字与扇形开合指示，但仍可添加并编辑 GIF 贴图。
- 视频放映模式下铰链角度会驱动视频进度，低延迟跟手；快速转动时会自动加速帧步进，必要时跳帧以追上当前角度。
- 长按贴纸可在贴纸中心出现删除 X，点击删除；删除时有弹性动画与震动反馈。
- 视频放映模式下长按任意位置会出现删除 X，点击删除视频并恢复角度指示；长按/删除均有震动反馈。

# 建议视频规格

为保证低延迟与更流畅的 seek，建议使用：

- 分辨率：720p（1280x720）或更低
- 帧率：30fps
- 关键帧间隔（GOP）：约 1s
- 编码：H.264（兼容性最好）

# 可选调参

如需进一步调“跟手/平滑”的取舍，可在 `app/src/main/java/com/example/hingemeter/MainActivity.kt` 里调整：

- `minCutoffHz`, `betaCutoff`：平滑强度（越大越稳，越小越灵）。
- `baseSeekIntervalMs`, `maxSeekIntervalMs`：寻帧节流（越小越灵，但更耗）。
- `fastStepDivisor`, `maxFrameStep`, `skipThresholdFrames`：高速转动时的加速/跳帧策略。

# license

MIT license
