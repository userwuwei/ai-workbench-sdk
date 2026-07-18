package com.cscjapp.aiworkbench.android;

/** Internal RecyclerView payload used to keep high-frequency stream binds narrowly scoped. */
final class WorkbenchStreamPayload {
  static final int NONE = 0;
  static final int TITLE = 1;
  static final int CONTENT = 1 << 1;
  static final int THOUGHT = 1 << 2;
  static final int COUNTER = 1 << 3;
  static final int AURA = 1 << 4;

  static final int TEXT_MASK = TITLE | CONTENT | THOUGHT;
  static final int FRAME_MASK = COUNTER | AURA;

  final int mask;

  WorkbenchStreamPayload(int mask) {
    this.mask = mask;
  }
}
