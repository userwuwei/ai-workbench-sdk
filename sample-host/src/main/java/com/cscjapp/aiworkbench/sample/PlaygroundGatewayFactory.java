package com.cscjapp.aiworkbench.sample;

import com.cscjapp.aiworkbench.api.ModelEndpoint;
import com.cscjapp.aiworkbench.api.Cancellable;
import com.cscjapp.aiworkbench.api.WorkbenchLaunchRequest;
import com.cscjapp.aiworkbench.core.ModelGateway;
import com.cscjapp.aiworkbench.core.ModelGatewayFactory;
import com.cscjapp.aiworkbench.core.ModelRequest;
import com.cscjapp.aiworkbench.core.ModelResponse;
import com.cscjapp.aiworkbench.core.ModelStreamDelta;
import com.cscjapp.aiworkbench.core.ModelStreamObserver;
import com.cscjapp.aiworkbench.model.openai.OpenAIModelGateway;
import java.util.concurrent.atomic.AtomicBoolean;

final class PlaygroundGatewayFactory implements ModelGatewayFactory {
  private final PlaygroundRuntime runtime;
  private final ScriptedModelGateway scripted;

  PlaygroundGatewayFactory(PlaygroundRuntime runtime) {
    this.runtime = runtime;
    scripted =
        new ScriptedModelGateway(
            message -> runtime.log("model", message), runtime::nextGeneratedPath);
  }

  @Override
  public ModelGateway create(WorkbenchLaunchRequest request, ModelEndpoint endpoint) {
    ModelGateway delegate;
    if (PlaygroundRuntime.MODE_REAL.equals(runtime.mode(request))) {
      runtime.log("gateway", "使用真实 OpenAI 兼容网关");
      delegate = new OpenAIModelGateway();
    } else {
      runtime.log("gateway", "使用离线 ScriptedModelGateway");
      delegate = scripted;
    }
    return new LoggingGateway(delegate);
  }

  private final class LoggingGateway implements ModelGateway {
    private final ModelGateway delegate;

    LoggingGateway(ModelGateway delegate) {
      this.delegate = delegate;
    }

    @Override
    public Cancellable stream(ModelRequest request, ModelStreamObserver observer) {
      runtime.log(
          "request",
          request.endpoint().modelId()
              + " · messages="
              + request.messages().size()
              + " · tools="
              + request.tools().size());
      StreamLog log = new StreamLog();
      AtomicBoolean terminal = new AtomicBoolean();
      Cancellable running =
          delegate.stream(
              request,
              new ModelStreamObserver() {
                @Override
                public void onDelta(String content, String reasoning) {
                  log.record(content, reasoning, 0);
                  observer.onDelta(content, reasoning);
                }

                @Override
                public void onStreamDelta(ModelStreamDelta delta) {
                  int toolLength = 0;
                  if (delta != null) {
                    for (com.cscjapp.aiworkbench.core.ToolCallStreamDelta tool :
                        delta.toolCalls()) {
                      toolLength += tool.arguments().length();
                    }
                    log.record(delta.content(), delta.reasoning(), toolLength);
                  }
                  observer.onStreamDelta(delta);
                }

                @Override
                public void onComplete(ModelResponse response) {
                  if (!terminal.compareAndSet(false, true)) return;
                  log.flush("完成");
                  runtime.log(
                      "response",
                      "finish="
                          + response.finishReason()
                          + " · toolCalls="
                          + response.toolCalls().size());
                  observer.onComplete(response);
                }

                @Override
                public void onError(Throwable error) {
                  if (!terminal.compareAndSet(false, true)) return;
                  log.flush("错误");
                  runtime.log(
                      "error",
                      error == null
                          ? "未知模型错误"
                          : error.getClass().getSimpleName() + " · " + error.getMessage());
                  observer.onError(error);
                }
              });
      return () -> {
        if (terminal.compareAndSet(false, true)) {
          log.flush("取消");
          runtime.log("cancel", "模型请求已由用户取消");
        }
        running.cancel();
      };
    }
  }

  private final class StreamLog {
    private long content;
    private long reasoning;
    private long toolArguments;
    private long nextSample = 1L;

    void record(String contentDelta, String reasoningDelta, int toolDelta) {
      content += contentDelta == null ? 0 : contentDelta.length();
      reasoning += reasoningDelta == null ? 0 : reasoningDelta.length();
      toolArguments += Math.max(0, toolDelta);
      long total = content + reasoning + toolArguments;
      if (total < nextSample) return;
      runtime.log(
          "stream",
          "增量累计 content="
              + content
              + " · reasoning="
              + reasoning
              + " · toolArgs="
              + toolArguments);
      if (total < 256) nextSample = 256;
      else if (total < 4_096) nextSample = ((total / 1_024) + 1) * 1_024;
      else nextSample = ((total / 10_000) + 1) * 10_000;
    }

    void flush(String stage) {
      runtime.log(
          "stream",
          stage
              + " · content="
              + content
              + " · reasoning="
              + reasoning
              + " · toolArgs="
              + toolArguments);
    }
  }
}
