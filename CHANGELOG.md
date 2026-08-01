# Changelog

## 2.0.23

- `read_plan` 以当前 revision 中的真实完整证据块判定多信号覆盖；未完成结果返回 `evidence_frontier`，只有存在未返回的真实源码候选时才给出增量 `next_read_plan_delta` 与 `read_plan` 建议。
- 本地规划器复用本次已读取源码，统一索引 signals、命中行与最内层结构，不再逐 evidence target 重读文件或重复计算 revision；成功路径输出分阶段耗时日志。
- Code Agent 为同一路径和 revision 记录证据增量并返回 `plan_progress`；重复证据不再推荐等价 `read_plan`。历史投影只保留同一路径和 revision 最新一次计划的完整 evidence。

## 2.0.22

- Code Agent 按文件路径与源码 revision 统计成功的局部 `read_file`；同一文件第二次及后续局部读取仍正常返回代码，并明确推荐下一步使用一次 `read_plan` 收集剩余证据。
- 全文读取、结构摘要、失败调用和不同 revision 不计入局部读取次数；新增提示词规则与回归测试，避免模型继续对同一文件分段读取。

## 2.0.21

- `search_replace` 由 40 行/3000 字符硬上限改为 `low/medium/high/critical` 风险分级；唯一命中的连续大区段可写入，大段清空、广覆盖显著缩减和多函数显著缩减以 `search_replace_destructive_change` 原子拒绝。
- Code Agent 提示词改为默认推荐 2～6 行精确窗口，允许来自当前 revision 且唯一命中的连续函数/语义区段；恢复路径同时兼容新 `destructive_change` 与旧 `old_too_large` 错误码。
- ToolResult 可返回每项风险信息及顶层 `high_risk_replacement_indexes/requires_verification`；历史压缩保留有界风险摘要，Android 工作台将可恢复的零写入结果收敛为单张 warning Reason 卡，high-risk 成功卡明确提示后续项目验证。

## 2.0.20

- 工具协议新增 Endpoint `STRICT/BEST_EFFORT` 与 Tool Schema 双重开关；严格工具向兼容网关发送 `strict:true`，旧构造方式继续默认非严格。
- 非法工具参数不再修补尾括号或降级为 `__raw_arguments`，而是在 Dispatcher 之前形成精确错误；兼容接口最多纠错一次，严格接口直接报告 Provider 协议异常。
- `create_file/search_replace` 收敛为严格 Schema，移除单条替换、`expected_matches` 等旧参数分支；默认轻量 Web 验证与完整 Browser 验收继续隔离。

## 2.0.19

- 模型请求上下文采用 258k 输入预算，在 78%/88% 高水位按完整工具事务进行非破坏性软/强压缩；canonical history、revision、evidence 和验证状态保持不变。
- 同一压缩阶段内已发送事务冻结并只在尾部追加，移除 80 消息/120k 字符的常态滑窗及 latest write/full-read 的历史追溯改写；压缩后建立新的稳定缓存前缀。
- OpenAI 兼容网关可记录 input/cached/uncached/output tokens，不支持 `stream_options.include_usage` 的服务自动回退；Android 上下文 UI 展示最终请求投影和 78%/88% 状态。

## 2.0.18

- `quality_review` 在真实验证齐全后与 `recommended_next_action` 同轮可见，并提供不接收 `path` 的紧凑质量结论形状。
- 模型只需提交 `passed`、`blocking_gaps` 与 `minimal_version_risk`；计划质量模式和 Browser 行为证据继续由 SDK 注入。
- 过早或非法质量调用不再污染验证状态；真实质量 blocker 进入产品修复，缺少质量证据的重复 finalize 明确不可原样重试。

## 2.0.17

- Browser Test 在 SDK 预检、规范化 Hash 与宿主执行之前统一解析入口路径；缺省、相对和绝对入口指向同一文件时产生相同执行 Hash。
- Browser 执行参数与校验参数不一致时返回可操作的环境诊断，并明确原样重试 Browser 或 finalize 无法恢复。
- finalize 区分从未验证与环境证据无效：后者不可原样重试，继续保持 Browser、revision 与完成证据门禁。

## 2.0.16

- Android 参考工作台由 40 轮硬失败改为每 20 次模型请求可恢复暂停；点击“继续生成”会在同一 run、同一消息与策略生命周期中授权下一段 20 轮。
- 暂停仅发生在完整模型响应和全部工具结果之后，不触发 run finish、不会添加 USER 消息或重复执行工具；第 20 轮已成功终态时直接完成。
- 暂停卡片复用现有 Summary action 并持久化 UI；仅同进程/ViewModel 生命周期可继续，进程恢复后的旧检查点明确禁用。

## 2.0.15

- `search_replace` 的 old 过大、上下文无效和批量冲突统一进入可恢复编辑路径，候选锚点已存在时直接重试编辑。
- 大写入失败的模型历史投影保留全部失败索引、错误码、命中数据和最多 8 个有界候选，不再丢失可执行的修复证据。
- 通用 Prompt 明确 old 的 2～6 行短窗口、40 行/3000 字符上限、唯一单行 substring 及候选存在时不重新读取的契约。

## 2.0.14

- 重新发布 `2.0.13` 的 ToolSelection 过度拦截收口修复；JitPack 的 `2.0.13` 版本键此前已缓存到无制品的旧提交，无法解析正式标签制品。

## 2.0.13

- ToolSelection 恢复为模型工具展示与推荐机制；模型返回的已注册工具统一进入 Dispatcher 和既有 ToolPolicy，不再因本轮未展示而产生 `tool_not_selected` 死循环。
- 有效编辑计划在 syntax、browser 与 quality 阶段持续展示原子的 `search_replace`；新写入仍会使当前 revision 的验证和质量证据失效。
- implement 编辑证据按计划序号隔离，replan 不再复用旧写入完成新步骤；最后一个编辑步骤在当前 revision 的首项必需验证通过后才结束。
- 通用 Prompt 明确 `recommended_next_action` 是首选而非禁令，并允许复杂修改在 syntax_check 前连续执行多个精确 `search_replace`。

## 2.0.12

- 重新发布 `2.0.11` 的 Browser Test 编辑拦截收口修复；`2.0.11` 的首次 JitPack 构建因构建节点无法解析 Gradle 插件仓库而未生成制品。

## 2.0.11

- `browser_test` 混合故障优先保留独立产品根因，同时输出互不污染的 `reading_brief` 与 `test_retry_brief`，不再让错误 baseline 遮蔽真实运行时故障。
- Coordinator 对混合根因同时开放 `search_replace` 与 browser 重试路径；纯测试、环境和纯产品失败仍保持各自阶段边界，只有纯产品失败锁定回归 Hash。
- `tool_not_selected` 改为不可直接重试，并回显本轮工具与最近推荐动作；交互检查必须描述动作后可观察终态，不再用静态可见性硬凑动态断言。

## 2.0.10

- `browser_test` 共享预检拒绝同一 clean baseline 下互为正反的 `false_to_true` 条件，动作后的关闭、消失和复位统一使用 `eventually_true`。
- syntax、browser 与 quality 阶段不再同时开放编辑；只有真实 syntax、产品或 quality 失败才恢复 `search_replace`，未选择工具继续零执行返回 `tool_not_selected`。
- 测试根因优先留在 browser 重试路径，纯产品失败才锁定回归 Hash；质量与完成证据移除场景自由描述，只保留真实 action/checkpoint/postcondition。

## 2.0.9

- `wait_for false_to_true` checkpoint 现在使用与最终断言相同的场景动态覆盖审计，成功 baseline→post 证据不再被误报为产品故障。
- Browser Test 规范化 Hash 由 SDK 与宿主共享，改变回归语义会在 WebView 启动前零执行失败，并保留结果侧校验兜底。
- 轮次工具选择重新成为实际执行边界；未提供的已注册工具返回配对的 `tool_not_selected`，源码写入后的 syntax 阶段不再被历史工具调用绕过。
- 交互审计缺失或自相矛盾统一隔离为不可缓存的环境失败，不生成源码读取或测试修改 Brief。

## 2.0.8

- 收敛 `browser_test` 合法形状与稳定状态断言，`wait_for false_to_true` 可作为交互动态证据。
- 派生阻断不再干扰产品回归 Hash，浏览器通过后的推荐动作服从当前 revision 的真实缺失证据。
- 测试纠错结果不再重复投影 validation issues，质量与完成验证只使用 SDK 派生行为证据。

## 2.0.7

- `browser_test` 允许 required ID 混合静态与动态场景，并新增可选 `wait_for` checkpoint；共享预检对行为重复、动作预算、只读表达式和 checkpoint 超时一次聚合校验。
- 产品故障后的通过结果必须保持原测试语义 hash 才能成为回归证据；质量结果由成功场景的 action/checkpoint/postcondition trace 派生行为证据。
- 浏览器模型投影按场景和 expectation/checkpoint/action 身份保留失败，避免不同断言被错误合并，并继续执行 48 项有界投影。

## 2.0.6

- 新增 SDK/宿主共享的 `BrowserTestContractValidator`，一次聚合全部场景、动作、断言、计划 ID 与动态覆盖问题；非法计划零启动返回 `validation_issues`，每场景动作上限提升为 20、事务上限为 60，并允许只读 `parseInt/parseFloat`。
- 浏览器历史投影完整保留失败原因、校验问题、场景 failures、实际状态、成功 action trace、联合读取 brief 和推荐动作；失败项跨场景去重并限制为 48 项，超限返回分类计数。
- 计划要求的每个交互场景都必须具有 `actions + false_to_true` 动态证据，避免仅凭按钮或 selector 存在进入质量审查。

## 2.0.5

- 同一文件 revision 的读取证据改为单调合并，成功写入或完整证据不再被后续局部片段降级；未写盘的精确替换失败不再清除有效证据。
- `search_replace` 默认依赖 exact-old 与原子预检，不再要求前置读取门禁；整文件 `rewrite` 继续受保护，并保留 `legacy` 构造期回滚模式。
- 写入、验证、质量和可恢复失败阶段持续开放 `search_replace`，同时用现有 `recommended_next_action` 指向验证、修复或浏览器重试，不扩大其他写工具可见范围。
- 通用提示词改为最小充分证据决策；非法 `plan_task` 参数显式返回 `invalid_tool_arguments`，OpenAI 网关仅尝试一次尾括号修复后保留原始错误参数，并记录真实 outbound JSON 的 `serialized_chars`。

## 2.0.4

- 轮次工具列表继续用于引导模型，但 Core 不再以 `tool_not_available_for_round` 拒绝已注册工具；路径、写入授权、精确替换和浏览器表达式安全策略保持不变。
- `plan_task` 在 ADAPTIVE 的读取前后、写入后及验证/质量/恢复阶段持续可见，FORCE 仅在首次写入前保留计划门禁，支持基于真实新证据重新规划。
- ADAPTIVE 不再把计划存在或所有计划步骤完成作为终态硬证据；完成只依赖当前代码 revision 所需的验证与质量结果。
- 浏览器计划不匹配、桥接结果异常和 Quality 缺证据均归一化为 `passed=false` 的普通结构化结果，不再产生跨阶段硬错误；失败的新浏览器结果不会覆盖旧成功事务。
- `missing_stage` 与 `recommended_next_action` 统一复用动态工具选择的同一状态判断，保证建议工具在下一轮可见。

## 2.0.3

- 浏览器验证收敛为当前计划与内部 revision 上的一次已接受事务，不再使用动态 `covered_interaction_check_ids` 作为 quality/finalize 的第二套完成账本。
- `browser_test` 在启动前继续精确校验计划场景 ID；交互页面还要求至少一个 `actions + false_to_true` 场景，成功事务则校验完整场景结果、源码 revision 与计划 hash。
- `quality_review` 直接消费最新浏览器事务，`finalize_task(completed)` 只依赖受管计划完成状态；终态验证器不再从历史重新计算浏览器和质量证据。
- 显式验证步骤只检查自己的 `required_tools`，轮次工具选择同时成为实际执行边界，避免隐藏工具被历史调用绕过。
- 浏览器历史仅保留场景通过/失败摘要，不再向模型暴露交互覆盖账本、源码 hash 或缓存 hash。

## 2.0.2

- `plan_task.interaction_checks[]` 保留显式稳定 `check_id`，并通过 `plan_state.required_interaction_check_ids` 持续暴露给模型。
- `browser_test` 在宿主启动前校验场景 ID 是否一次性完整覆盖当前计划；不匹配时返回结构化差异且 WebView 启动次数为零。
- 最新浏览器事务按同一 plan、源码 revision 和完整覆盖登记，`quality_review` 自动绑定真实覆盖 ID；写入或重新规划立即使旧浏览器与质量证据失效。
- `finalize_task(completed)` 的浏览器/质量前置条件改在工具执行前返回明确下一阶段，宿主终态验证器不再重复注入笼统交互 blocker。
- 浏览器历史只压缩结果，完整保留合法 `actions`、`expectations` 和 `transition` 参数，不再向工具参数写入 `request_projection`。

## 2.0.1

- `read_file` 与 `read_plan` 在代码任务各阶段保持可见，移除普通读取的策略失败；首次接触当前 revision 时默认建议完整读取，跨区域证据读取由模型自主判断。
- 完整或局部 `read_file`、成功写入和 `ready_for_edit` 的 `read_plan` 按当前 revision 提供对应编辑证据，磁盘变化继续使旧证据失效。
- 最近一次不超过 48KB 的成功写入保留到浏览器验证通过，避免语法检查后因源码参数过早压缩而再次读取。
- 发现步骤按实际必需工具推进，`list_dir` 不再替代明确要求的源码读取。

## 2.0.0

- Agent Core 新增轮次级动态工具面，Code Agent 按计划、读取、编辑、语法、浏览器和质量阶段主动收窄模型可见工具；常规源码收集只暴露目标驱动 `read_plan`。
- 读取后编辑门禁只认可当前 revision 且 `ready_for_edit=true` 的 `read_plan`；`read_file` 仅在 `search_replace` 精确失败后用于同路径、最多 80 行的真实锚点恢复。
- 浏览器验证结果按 `failure_kind` 路由：测试计划问题继续浏览器计划，产品代码问题进入一次 `read_plan`，环境问题留在浏览器验证阶段。
- 模型请求历史压缩目标驱动 `browser_test` 的场景明细，仅保留 source revision、plan hash、覆盖和失败分类。

## 1.2.4

- Code Agent 在宿主注册新版 `read_plan` 时启用目标驱动证据阅读协议，引导模型一次声明目的、证据类型和定位信号，并依据 `ready_for_edit` 收敛到编辑。
- 读取后编辑门禁升级为文件 revision 证据：仅返回路径不再授权编辑，成功写入或磁盘内容变化会使旧证据立即失效。
- 模型请求历史对 `read_plan` 保留单份 `evidence[]` 与精确编辑锚点，移除内部批读的重复 `items/content` 负担。

## 1.2.3

- 完整回撤 1.2.2 的交互检查分级、统一交互证据、Meta 预检、请求历史投影扩展和计划卡调整。
- SDK 运行代码恢复为 1.2.1 行为；保留 1.2.2 Tag 与 JitPack 制品作为不可移动发布历史。
- Maven 坐标、公共 Java API、Session schema v3、文件工具和流式体验保持不变。

## 1.2.2

- 交互检查分为最多 3 项确定性 required 与非阻塞 advisory，重规划降级的检查会保留为可见风险。
- 计划、浏览器审计、质量审查和终态统一使用当前文件 revision 的稳定 check ID 证据，普通 browser 通过不再冒充动态交互覆盖。
- `browser_test.run_steps` 在启动 WebView 前校验 action、check ID 配对、断言顺序与只读脚本；重复的完全相同失败调用快速返回。
- 模型请求投影压缩旧读取正文和浏览器明细，固定保留最新计划、计划状态与产物摘要，日志增加投影字符和压缩组统计。
- Android 计划卡区分核心待验证与 advisory 风险，质量或终态证据不足时展示具体 missing check 和 next action。

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
