# Changelog

## 1.2.1

- 流式状态改为不可变顺序事件；`已思考 / 已输入 / 已写入 / 已接收` 的短状态至少可见 220ms，高频数字仍按帧合并。
- 模型连续 10 秒无有效增量时显示本地等待秒数；新增不含源码的 `headers / first_delta / delta_gap / finish_reason / done / eof` 紧凑诊断日志，网络超时配置保持不变。
- 仅为模型请求压缩已完成的大型写工具历史，并将 tool arguments/results 纳入 120,000 字符预算；本地完整会话与失败修复锚点保持可用。
- 受管计划按工具角色、规范路径、运行代次和文件 revision 登记真实证据；每个计划文件独立推进，写入会作废旧验证与质量证据。
- 交互检查获得稳定 `check_id`，供宿主浏览器验证器证明操作前后状态真实变化，静态页面不受影响。

## 1.2.0

- 新增 `ADAPTIVE / FORCE / SKIP` 自适应规划模式，简单任务不增加计划轮次，复杂任务在首次写入前建立短计划。
- 新增轻量受管计划协调器，按当前任务真实读取、写入、验证和质量证据推进，不修改 Core API 或 Session schema v3。
- 兼容旧计划字段、数字步骤和缺失字段，不因计划格式异常要求模型重新提交。
- 新写入自动作废旧验证与质量证据；终态缺少计划、当前验证或质量证据时由 Validator 阻止完成。
- 计划卡优先展示归一化目标、文件、验证策略和 3～5 个步骤，工具失败不会提前推进或全部标绿。

## 1.1.7

- 完整回撤 `1.1.6` 的计划归一化、计划进度跟踪、Session schema v4 与计划独立日志。
- SDK 运行行为恢复为 `1.1.5`，保留 `1.1.6` Tag 与制品作为不可移动历史版本。
- Maven 坐标、公共 Java API、工具协议及其他 Code Agent 能力保持不变。

## 1.1.6

- 固定 `plan_task.steps[]` 的 ID、标题、阶段、必需工具和验收条件契约。
- 兼容旧步骤字段与字符串形式的质量/设计规格，统一归一化 Native 和 Legacy 计划结果。
- 计划卡默认显示目标、文件、验证策略和核心步骤，并按真实工具结果推进。
- 工具失败、浏览器布局失败和质量阻塞不再推进计划；仅在终态 Validator 通过后全部完成。
- 会话计划状态升级到 schema v4，新任务不复用旧计划，历史运行态不再恢复为进行中。
- 增加不含源码和完整参数的紧凑计划事件日志，记录建立、推进、失败与完成。

## 1.1.5

- 修复 `read_file_batch` 已返回文件仍被读取后编辑门禁误拦截的问题。
- READ 工具通过 `read_paths` 声明实际返回的文件；请求但被截断的目标不再获得编辑权限。
- 批量读取预算截断会准确回填 `dropped_targets`，真实缺少证据时提供明确提示和单行诊断。

## 1.1.4

- 固化跨语言文件工具选择协议：新路径使用 `create_file`，已有文件统一使用 `search_replace`。
- 模型可见的 `create_file` Schema 不再暴露 `overwrite`，内部冲突选择兼容性保持不变。
- 标准 `search_replace` 支持一次原子提交多个非重叠 `replacements[]`。
- 新任务开始前将已完成代码任务压缩为结果摘要，不再把历史完整文件参数作为工具选择示例。

## 1.1.3

- 模型请求、响应和错误改为接近各编译器现有风格的中文结构化日志。
- 工具续轮仅打印新增消息，不再重复输出整个请求 JSON、历史消息和工具 Schema。
- 长内容保留真实换行并移除分段计数前缀，继续保证 Authorization/API Key 不进入日志。

## 1.1.2

- 增加轻量 `WorkbenchLogger` 与 Logcat 实现。
- 可按宿主配置打印完整模型请求、最终 content/reasoning/tool calls、耗时和错误。
- 不打印 Authorization/API Key，长内容按 Logcat 安全长度分段输出。

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
