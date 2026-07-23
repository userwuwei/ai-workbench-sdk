package com.cscjapp.aiworkbench.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class WorkbenchStreamUiMailboxTest {
  @Test
  public void rapidStateChangesAndTerminalRemainDistinctAndOrdered() {
    WorkbenchStreamUiMailbox mailbox = new WorkbenchStreamUiMailbox();
    mailbox.offer(update(1L, "REASONING", false));
    mailbox.offer(update(2L, "INPUT", false));
    mailbox.offer(update(3L, "RECEIVE", false));
    mailbox.offer(update(4L, "WRITE", false));
    mailbox.offer(update(5L, "TERMINAL", true));

    List<WorkbenchViewModel.StreamUiUpdate> drained = mailbox.drain();

    assertEquals(5, drained.size());
    assertEquals(
        Arrays.asList("REASONING", "INPUT", "RECEIVE", "WRITE", "TERMINAL"),
        Arrays.asList(
            drained.get(0).kind,
            drained.get(1).kind,
            drained.get(2).kind,
            drained.get(3).kind,
            drained.get(4).kind));
    assertTrue(drained.get(4).terminal);
  }

  @Test
  public void onlyConsecutiveUpdatesOfSameStateAreCoalesced() {
    WorkbenchStreamUiMailbox mailbox = new WorkbenchStreamUiMailbox();
    mailbox.offer(update(1L, "INPUT", false, WorkbenchStreamPayload.CONTENT));
    mailbox.offer(update(2L, "INPUT", false, WorkbenchStreamPayload.COUNTER));
    mailbox.offer(update(3L, "RECEIVE", false, WorkbenchStreamPayload.COUNTER));

    List<WorkbenchViewModel.StreamUiUpdate> drained = mailbox.drain();

    assertEquals(2, drained.size());
    assertEquals("INPUT", drained.get(0).kind);
    assertEquals(2L, drained.get(0).sequence);
    assertEquals(
        WorkbenchStreamPayload.CONTENT | WorkbenchStreamPayload.COUNTER,
        drained.get(0).reasonMask);
    assertEquals("RECEIVE", drained.get(1).kind);
    assertFalse(drained.get(1).terminal);
  }

  @Test
  public void identicalKindsFromDifferentRunsNeverCoalesce() {
    WorkbenchStreamUiMailbox mailbox = new WorkbenchStreamUiMailbox();
    mailbox.offer(update(1, 1L, "REASONING", false, WorkbenchStreamPayload.COUNTER));
    mailbox.offer(update(2, 2L, "REASONING", false, WorkbenchStreamPayload.COUNTER));

    assertEquals(2, mailbox.drain().size());
  }

  private static WorkbenchViewModel.StreamUiUpdate update(
      long sequence, String kind, boolean terminal) {
    return update(sequence, kind, terminal, WorkbenchStreamPayload.COUNTER);
  }

  private static WorkbenchViewModel.StreamUiUpdate update(
      long sequence, String kind, boolean terminal, int reasonMask) {
    return update(1, sequence, kind, terminal, reasonMask);
  }

  private static WorkbenchViewModel.StreamUiUpdate update(
      int roundId, long sequence, String kind, boolean terminal, int reasonMask) {
    return new WorkbenchViewModel.StreamUiUpdate(
        roundId,
        sequence,
        kind,
        sequence,
        null,
        null,
        WorkbenchStreamPayload.NONE,
        null,
        null,
        reasonMask,
        false,
        !terminal,
        terminal);
  }
}
