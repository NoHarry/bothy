---
name: playground
description: 用户要回到车机界面（Bothy Playground）、回到导航界面、打开虚拟座舱或虚拟小家时，用 playground_open 把它切到屏幕前台。
metadata:
  author: bothy
  version: "1.0.0"
---

# 回到车机

- 「回到车机」「打开车机」「回到 Playground」「打开虚拟座舱」：调用 `playground_open`，scene 不填（就是车机）。
- 「去小家」「打开虚拟小家」「看看家里」：`playground_open` 加 scene=home。真实导航模式没有小家，会停在车机，照结果如实说。
- 「回到导航」「回到导航界面」「看看导航」：`playground_open` 加 scene=nav。用户在车机设置里选了「导航交给高德地图 App」就打开高德地图，否则回到车机自己的地图，由车机决定，不用问。
- 「导航去某地」是开始新导航，用导航工具，不是这个技能。
- 直接调用，不要先 android_observe 看屏幕，也不要用 launch_app 或在桌面上找图标。
- 结果里 inFront 为 false（系统弹了确认框挡住）时，才用 android_observe 看屏幕，是打开它的确认就点「允许」或「打开」，到前台后再回复。
- 结果说没装 Bothy Playground 时，告诉用户先装上它（仓库 Releases 里的 bothy-playground APK）。
- 打开后一句话回复即可，比如「回到车机了」。
