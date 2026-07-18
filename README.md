# AI Workbench SDK

面向任意 Android App 的通用 Agent 工作台。SDK 只实现模型交互和 Agent 基础设施；语言、业务、权限、提示词、工具和验收规则全部由宿主 App 注入。

## 边界一览

```text
┌────────────────────── 通用 SDK 实现 ──────────────────────┐
│ Workbench Activity / 流式 UI / DialogX 决策 / 历史恢复     │
│ OpenAI-compatible transport / Agent loop / native tools   │
│ Prompt composer / Tool dispatcher / Policy / Validator    │
│ 安全文件工具（可选）/ 同一次调用的文件冲突处理 / 取消       │
└───────────────────────────┬───────────────────────────────┘
                            │ WorkbenchDefinition
┌────────────────────── App 定制实现 ───────────────────────┐
│ PromptContributor：角色、业务规则、语言语义                │
│ ContextProvider：项目、文件、媒体任务或业务上下文           │
│ AgentTool：编译、浏览器、转码、上传、领域操作               │
│ ToolPolicy：路径、权限、额度、用户确认等边界                │
│ TaskValidator：语法、运行、布局、转码产物等完成门禁          │
│ WorkbenchHost：打开产物、刷新列表、登录、会员、模型管理      │
└───────────────────────────────────────────────────────────┘
```

SDK 不导入宿主包，也不按 C、C++、Java、Python、HTML 或视频等产品名称分支。

## 模块

| 模块 | 通用职责 |
|---|---|
| `workbench-api` | 无 Android/OkHttp/Gson/DialogX 泄漏的稳定接入契约 |
| `workbench-core` | Agent 循环、native/legacy 协议、上下文裁剪、工具策略、完成验证 |
| `workbench-model-openai` | OpenAI-compatible SSE/JSON、reasoning、碎片化 tool calls |
| `workbench-tools-file` | 可选安全工作区文件工具与覆盖/新建冲突策略 |
| `workbench-android` | 通用 Activity、流式卡片、用户决策、会话持久化 |
| `workbench-starter` | 宿主唯一依赖入口 |
| `sample-host` | 与编译器无关的最小接入示例 |

## 五步接入

```gradle
repositories {
    maven {
        url "https://jitpack.io"
    }
}

implementation "com.github.userwuwei.ai-workbench-sdk:workbench-starter:1.0.0"
```

```java
AIWorkbench.install(application,
    WorkbenchSdkConfig.builder()
        .registerFactory("video-factory", request -> new VideoWorkbenchDefinition(request))
        .modelConfigProvider(request -> currentModel())
        .accessPolicy(appAccessPolicy)
        .build());

AIWorkbench.open(activity,
    WorkbenchLaunchRequest.builder("video-factory")
        .workspaceId(jobDirectory)
        .initialDemand("把视频转换为 H.264 MP4，并保留原分辨率")
        .build());
```

`VideoWorkbenchDefinition` 只需注入：转码提示词、输入媒体上下文、`probe_media`/`convert_video`/`verify_output` 工具、路径与额度策略、产物校验器和打开结果的 Host 行为。无需复制 Activity、模型流、历史、取消或 Dialog 代码。

完整步骤见 [`docs/INTEGRATION.md`](docs/INTEGRATION.md)，架构决策见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 文件冲突语义

`ExistingFileConflictPolicy` 在同一次 `create_file` 调用中异步请求用户选择：

```text
模型 tool_call → 路径预检 → DialogX 选择 → 改写本次 arguments → 执行原内容
```

不会先返回 `file_already_exists`，不会额外消耗模型修复轮次。新建路径按 `name-1.ext`、`name-2.ext` 递增，并在结果中返回 `requested_path`、`resolved_path` 和 `conflict_resolution`。

## 流式进度语义

SDK 将模型增量统一映射为工作台计数状态：reasoning 为“已思考”、可见内容为“已输入”、标准写工具参数为“已写入”，其他 App 自定义工具参数为“已接收”。`ModelStreamDelta` 同时携带 content、reasoning 和碎片化 native tool calls；原有 `onDelta(content, reasoning)` 通过默认方法继续兼容。工作台按 `Choreographer` 帧合并并使用 RecyclerView payload 定点刷新：数字最多每帧一次、文本约 30 FPS，流光绘制缓存路径与渐变，长参数流不会重建列表或反复重启数字动画。

## 构建与发布

```bash
./gradlew test lint assembleDebug :sample-host:assembleDebug
./gradlew publishToMavenLocal
```

发布前使用正式版本模拟 JitPack 产物并检查 POM：

```bash
./gradlew clean test lint :workbench-android:assembleRelease \
  :workbench-starter:assembleRelease :sample-host:assembleDebug \
  publishToMavenLocal -PAIW_VERSION=1.0.0
```

正式发布通过不可移动的语义版本 Git Tag 完成。JitPack 构建时从 `VERSION`
环境变量读取版本，所有模块统一发布到
`com.github.userwuwei.ai-workbench-sdk`。开发期可显式使用 Gradle composite
substitution；正式构建固定依赖已发布版本，不使用动态版本或 Snapshot。
