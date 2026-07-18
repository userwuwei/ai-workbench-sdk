# Changelog

## 1.1.1

- 新增可选纯 Java `workbench-code-agent` 模块。
- 新增语言 Profile、验证合同、工具角色与 Agent 运行生命周期。
- 通用化计划、读取后编辑、真实验证、质量门禁和终态工具。
- Playground 增加普通工作台与 Code Agent 双模式。
- `1.1.0` 源码门禁通过，但 JitPack 在制品收集后发生平台超时；按不可移动 Tag 规则改发本补丁版本。

## 1.0.0-SNAPSHOT

- 提供语言与产品无关的 Agent Core、Android 工作台和单依赖 starter。
- 支持 OpenAI-compatible SSE/JSON、reasoning 与碎片化 native tool calls。
- 支持 App 注入 prompt、context、tool、policy、validator、host、model 和 access provider。
- 提供可选安全文件工具及同一次调用内的覆盖/新建冲突处理。
- 提供版本化 UI/协议历史、原子持久化、进程重建历史清理和上下文边界。
- 使用 SDK 自有 `ViewModelProvider.Factory` 创建工作台状态，兼容宿主的 Lifecycle 版本并避免包级 ViewModel 反射失败。
- 增加非编译器 sample host，并完成 HTML/CSS/JavaScript App 试点接入。
- 补齐结构化模型流事件与 native tool call 参数增量，旧 `onDelta` 接入保持兼容且不重复回调。
- 完整恢复“已思考 / 已输入 / 已写入 / 已接收”数字状态及逐位滚动动画。
- 流式 UI 改为按帧合并与 payload 局部绑定，文本约 30 FPS、数字最多每帧一次，并保留完整流式内容。
- 流光路径、周长和渐变按尺寸缓存，后台、遮挡、detach 和历史恢复时停止无效动画。
- 数字动画使用最新目标队列，不再因高频计数取消重启；Legacy JSON 改为线性增量解析。
