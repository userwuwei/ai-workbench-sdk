# Architecture

## 分层

```text
[workbench-android]  通用 UI、生命周期、会话、用户决策
          ↓
[workbench-core]     Agent 循环、Prompt、Tool、Policy、Validation
       ↙      ↘
[model-openai]   [tools-file]      可替换/可选基础实现
          ↓
[workbench-api]      稳定宿主契约
          ↑
[Host App Definition] 提示词、上下文、工具、边界、验收、宿主动作
```

## 通用 SDK 所有权

- 模型请求、SSE/JSON 流、reasoning 和 native tool-call 组装。
- 多轮 Agent 状态机、最大轮次、取消、错误和终态工具。
- Prompt 分段排序和预算。
- Tool 注册、串行调度、异步 Policy continuation。
- TaskValidator 确定性完成门禁。
- UI、历史、进程重建后的协议历史清理。
- 可选 canonical 工作区文件工具。

## App 所有权

- 领域身份：HTML 编译器、Python IDE、视频工厂等。
- 模型与账号：endpoint、签名头、登录、会员、额度。
- 业务上下文：当前工程、选中文件、输入媒体、任务参数。
- 工具实现：编译、运行、浏览器、转码、上传等。
- 行为边界：允许路径、文件角色、资源限制、用户确认。
- 结束证据：语法、运行、布局、产物、业务状态。

## 依赖规则

1. `workbench-api` 不依赖 Android 或第三方传输/UI 类型。
2. SDK 不得导入任何宿主包。
3. 宿主通过 `WorkbenchDefinition` 组合，不通过 SDK 内的产品分支定制。
4. Policy 只改写或拒绝当前工具调用；用户选择不是新的模型轮次。
5. Validator 的真实证据优先于模型自报。

## 终态流程

```text
finalize tool
  → 收集本轮真实 ToolResult evidence
  → 依次运行宿主 TaskValidator
  ├─ blocker：结构化反馈回模型并继续修复
  └─ passed：发出 task_completed/task_blocked/task_needs_user_input
```
