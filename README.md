# warsaw Fans Control

LSPosed 模块在 HyperOS 原有风扇设置页加入 80% 停充修正、风扇转速上限和实时测速。适配 REDMI K90 Ultra（`warsaw`）、HyperOS `OS4.0.8.0.XHPCNXM` 和 PowerKeeper `4.2.00`（40200）。

## 安装和使用

1. 下载并安装 `warsaw-Fans-Control-v0.3.2.apk`。同版本 ZIP 内含该 APK 和中文安装说明。[下载最新版本](https://github.com/SonnetoEnd/warsaw-Fans-Control/releases/latest)
2. 在 LSPosed 启用模块，确认作用域勾选「设置」`com.android.settings` 和「电量和性能」`com.miui.powerkeeper`。
3. 重启手机，打开系统原来的风扇设置页。
4. 保留「极速充电时启用」，在电池设置中选择 80% 充电保护。新增的「80% 停充时退出极速充电散热」默认开启。

打开「风扇转速上限」选择调节方式。

- **跟随系统**是新安装的默认值。退出自定义时还原驱动的原始转速表，原厂负责启停和选择档位。80% 停充修正可独立开关。
- **原厂 1 至 4 档**限制允许的最高档位，依次对应 12,000、14,000、16,000 和 19,800 rpm。升级会沿用 v0.2 已保存的档位。
- **自定义**打开 HyperOS 的数字滚轮，在 12,000 至 19,800 rpm 之间每格调整 100 rpm。应用后可点「自定义目标转速」再次编辑。首次使用前，在 SukiSU 中向「warsaw Fans Control」授予 Root。APK 自带限速驱动，仅对当前适配的 Haruhi `6.6.142-android15-8-4k-Haruhi-26w37f` 内核和风扇驱动生效。高于上限的档位使用新的目标，较低档位和停转仍由原厂控制。重启后，PowerKeeper 加载模块时会重新应用已保存的上限。

本机设备树 `/sys/firmware/devicetree/base/soc/xiaomi_fan/duty_speed_map` 中的占空比和目标 rpm 依次为 `(0,0) (51,12000) (65,14000) (73,16000) (98,19800)`。页面的「实时转速」读取 `real_speed`。实机内核日志记录过 20,416 和 20,457 rpm。19,800 rpm 表示四档控制目标，测速值可以超过它。

## 充电场景

PowerKeeper 的 `FanStateHandler` 初始化时读取 `IMiCharge.getQuickChargeType()`，随后接收 `miui.intent.action.ACTION_QUICK_CHARGE_TYPE`。原厂风扇把类型编号 ≥ 3 视为极速充电。电池广播中的电量和插电类型用于记录。风扇的最终档位还取决于场景、模式与温度。

有线极速充电时，模块在 PowerKeeper 自己的处理线程上检查 80% 保护模式、`smart_chg` 的保护位和驱动上报的 `buck eff_client:soc_limit 0`。确认停充后，模块在这一次风扇策略计算中将极速充电标志置为假，让原厂策略重新选择档位。解除停充时再用原厂快充状态重新计算。电池事件、快充事件和设置变更会触发检查，符合条件期间每 5 秒复查一次，以覆盖停充原因晚于电量广播更新的情况。

在适配机上，插电 100% 时读到快充类型 1、风扇 0 rpm。短暂开启 80% 保护后，`smart_chg` 从 0 变为 16，停充原因从 `term_recharge` 变为 `soc_limit`。该次实测时电量已满。

## 构建

安装 Python 3、JDK 17、Zig 0.15.2、Android SDK Platform 35 和 Build Tools 34.0.0，准备 Xposed API 82 的编译用 JAR。运行以下命令生成带签名的 APK 和配套 ZIP，输出位于 `dist/`。

```text
python build.py --android-sdk <Android-SDK目录> --xposed-api <api-82.jar路径> --zig <Zig路径>
```

构建使用本机 Android 调试签名。LSPosed 源码在 `lsp/`。APK 内置的限速驱动源码在 `kernel/`。
