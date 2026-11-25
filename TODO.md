# TODO

- [x] 排查 ColorOS Launcher 17.5.5 的大文件夹快速垂直缩放。在竖屏首页按住时钟小组件，菜单出现前、时钟自身缩小的阶段，文件夹背景和预览图标会一起快速缩放。已定位为原生 `DeviceProfile.updateAreaWidgetConfigIfNeed` 反复写入未适配竖屏高度（`371px`）与 `onMeasure` 适配高度互相覆盖所致，已通过拦截 `updateAreaWidgetConfigIfNeed` 统一使用竖屏适配尺寸修复。
