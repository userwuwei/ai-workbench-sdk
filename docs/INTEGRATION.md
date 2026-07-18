# Host Integration

## 1. 安装

宿主只依赖 `workbench-starter`，在 `Application.onCreate()` 注册一个或多个 `WorkbenchFactory`。

## 2. 定义工作台

实现 `WorkbenchDefinition`：

- `promptContributors()`：稳定角色、业务规则和工具使用规范。
- `contextProviders()`：每轮按需提供当前业务上下文。
- `tools()`：真实可执行工具；长任务通过 `ToolCallback.onProgress` 报进度。
- `toolPolicies()`：执行前边界和用户决策。
- `validators()`：终态真实验收。
- `host()`：打开产物、刷新数据、登录/会员/设置等宿主动作。
- `actions()`：可选工具栏和快捷 Prompt。

## 3. 启动

使用 `WorkbenchLaunchRequest` 传 definition、workspace、初始需求、选中产物、深度思考和少量字符串 extras。大对象和凭据不得放入 Intent extras。

## 4. 长任务与取消

工具必须立即返回 `Cancellable`。耗时操作放在 `ToolContext.backgroundExecutor()`；取消后不得再次回调成功。用户决策通过 `UserDecisionService` continuation 完成，不阻塞主线程。

## 5. 验收

终态工具实现 `requestsFinalize() == true`。代码类 App 可要求编译/运行证据；页面类 App 可要求布局审计；媒体 App 可要求输出文件存在、codec/container/时长正确。

## HTML 试点映射

| 宿主定制点 | HTML App 实现 |
|---|---|
| Prompt | `prompt.txt` + `agent_tool_skills.txt` |
| Context | 当前 Web 工程、选择文件、需求来源 |
| Tools | 现有 HTML 文件、语法和 Android WebView browser tools 适配器 |
| Policy | canonical 工作区 + create_file 覆盖/新建选择 |
| Validator | syntax/browser/layout/quality evidence 门禁 |
| Host | 项目树刷新、打开真实入口、登录/会员/模型/举报 |
