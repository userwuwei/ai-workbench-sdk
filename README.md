# AI Workbench SDK

Android 通用 AI 工作台 SDK。基础工作台只负责模型交互、流式 UI、工具调度、历史和宿主事件；代码任务能力通过可选的 `workbench-code-agent` 组合启用。

## 依赖

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.userwuwei.ai-workbench-sdk:workbench-starter:2.0.4'
}
```

`workbench-starter` 传递导出 API、Core、OpenAI 网关、标准文件工具、Android UI 和可选 Code Agent 组件。宿主只有显式创建 `CodeAgentPreset` 才会获得代码任务语义。

## 普通工作台

适用于视频转换、数据处理等非代码业务。宿主直接实现 `WorkbenchDefinition` 并注入自己的 Prompt、工具、策略和验证器：

```java
WorkbenchDefinition definition = new MyBusinessWorkbenchDefinition();
```

普通工作台不会自动附加 `plan_task`、读取门禁、代码验证或 `finalize_task`。

## 代码工作台

```java
CodeValidationContract verification = CodeValidationContract.builder()
    .defaultRequiredEvidence("compile_test")
    .exemptCompletionTypes("explain")
    .requireQualityReview("ui_product")
    .requireManagedPlan("code_generation", "feature_integration", "ui_product", "refactor")
    .build();

CodeLanguageProfile profile = CodeLanguageProfile.builder("my-language")
    .languageRules(languageRules)
    .verificationContract(verification)
    .metaToolExtensions(metaSchemaExtensions)
    .build();

CodeAgentPreset preset = CodeAgentPreset.builder(profile)
    .workspace(workspace)
    .planningMode(CodePlanningMode.ADAPTIVE)
    .languageTools(languageTools)
    .languagePolicies(languagePolicies)
    .languageValidators(languageValidators)
    .build();
```

在宿主 `WorkbenchDefinition` 中返回：

```java
public List<PromptContributor> promptContributors() {
    return preset.promptContributors();
}

public List<AgentTool> tools() {
    return preset.tools();
}

public List<ToolPolicy> toolPolicies() {
    return preset.toolPolicies();
}

public List<TaskValidator> validators() {
    return preset.validators();
}
```

Code Agent 通用层提供：

- 语言无关任务协议与 `plan_task / quality_review / finalize_task`。
- 当前任务轮次内“读取后才能编辑已有文件”的硬门禁。
- `create_file` 文件冲突用户选择覆盖或自动新建。
- 按 `completion_type` 检查真实验证证据和质量阻塞项。
- Native Tools 与 Legacy 协议一致的终态约束。
- 强制区分文件创建与编辑：新路径使用 `create_file`，已有文件修改使用 `search_replace`。
- 自适应精简受管计划：简单任务直接执行，复杂任务通过短 `plan_task` 按真实工具证据推进。
- `ADAPTIVE / FORCE / SKIP` 三种规划模式；宿主也可通过 `code_agent_planning_mode` 启动参数选择。
- 新写入会使旧验证和质量证据失效；只有 Validator 通过后计划才会完成。

Code Agent 的常规源码收集只向模型暴露目标驱动 `read_plan`。它必须返回文件 `revision`、非空 `evidence[]`，并以 `coverage_summary.ready_for_edit=true` 表示当前 revision 已具备编辑证据；成功写入或磁盘内容变化会使旧证据失效。`read_file` 仅在 `search_replace` 精确失败后临时开放，用于同路径的函数/类/方法或最多 80 行短锚点恢复，普通 `read_file/read_file_batch` 不再授权跨区域编辑。

语言 Profile 只保留语言规则、编译/运行工具、验证合同和语言专属验证器。

## 模型交互日志

SDK 默认不输出模型内容。需要调试模型交互时由宿主显式开启：

```java
AIWorkbench.install(
    application,
    sdkConfig,
    WorkbenchRuntimeOptions.builder()
        .logger(new AndroidWorkbenchLogger("AIWorkbench"))
        .build());
```

Logcat 使用可直接阅读的中文分段输出：

```text
[模型请求][request=1][stage=initial][model=...]
[message=1][role=user]
用户输入

[模型响应][request=1][finish_reason=...][elapsed_ms=...]
[本轮模型返回][request=1][content]...
[本轮模型返回][request=1][tools][toolName=create_file]...
```

首轮打印完整消息，工具续轮只打印本轮新增消息，避免重复输出整段历史和工具 Schema。请求日志不包含 Authorization 或 API Key；长内容仅按 Logcat 安全长度无标记分段，模型流式增量在本轮完成后汇总打印。

## Playground

运行 `sample-host` 可独立验证 SDK：

- 普通离线工作台；
- 通用 Code Agent 完整闭环；
- 真实 OpenAI 兼容模型；
- Native/Legacy 工具、文件冲突、长文本、长参数和流式动画场景。

离线模式不需要网络或 Token。真实模型配置只保存在 Playground 私有数据或本机 Gradle 用户属性中。

## 本地源码联调

宿主保持远程坐标不变，通过 composite build 替换：

```bash
./gradlew :app:assembleDebug -PAIW_USE_COMPOSITE=true
```

## 发布

```bash
./gradlew clean test lint :workbench-android:assembleRelease \
  :workbench-starter:assembleRelease :sample-host:assembleDebug \
  publishToMavenLocal -PAIW_VERSION=1.2.3

git tag 1.2.3
git push origin main
git push origin 1.2.3
```

正式 Tag 不移动；发布失败后使用新的补丁版本。
