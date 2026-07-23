package com.cscjapp.aiworkbench.android;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Lossless state-transition mailbox in front of LiveData's coalescing signal channel. */
final class WorkbenchStreamUiMailbox {
  private final ArrayDeque<WorkbenchViewModel.StreamUiUpdate> queue = new ArrayDeque<>();

  synchronized void offer(WorkbenchViewModel.StreamUiUpdate update) {
    if (update == null) return;
    WorkbenchViewModel.StreamUiUpdate last = queue.peekLast();
    if (last != null
        && !last.terminal
        && !update.terminal
        && last.roundId == update.roundId
        && last.kind.equals(update.kind)) {
      queue.removeLast();
      queue.addLast(last.mergeSameKind(update));
    } else {
      queue.addLast(update);
    }
  }

  synchronized List<WorkbenchViewModel.StreamUiUpdate> drain() {
    if (queue.isEmpty()) return Collections.emptyList();
    List<WorkbenchViewModel.StreamUiUpdate> drained = new ArrayList<>(queue);
    queue.clear();
    return drained;
  }

  synchronized void clear() {
    queue.clear();
  }
}
