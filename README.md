# AI Workbench SDK

Android 通用 AI 工作台 SDK。基础工作台只负责模型交互、流式 UI、工具调度、历史和宿主事件；代码任务能力通过可选的 `workbench-code-agent` 组合启用。

## 依赖

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.userwuwei.ai-workbench-sdk:workbench-starter:1.1.0'
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
    .build();

CodeLanguageProfile profile = CodeLanguageProfile.builder("my-language")
    .languageRules(languageRules)
    .verificationContract(verification)
    .metaToolExtensions(metaSchemaExtensions)
    .build();

CodeAgentPreset preset = CodeAgentPreset.builder(profile)
    .workspace(workspace)
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

语言 Profile 只保留语言规则、编译/运行工具、验证合同和语言专属验证器。

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
  publishToMavenLocal -PAIW_VERSION=1.1.0

git tag 1.1.0
git push origin main
git push origin 1.1.0
```

正式 Tag 不移动；发布失败后使用新的补丁版本。
