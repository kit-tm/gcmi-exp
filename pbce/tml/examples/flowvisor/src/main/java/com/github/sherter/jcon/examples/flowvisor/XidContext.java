package com.github.sherter.jcon.examples.flowvisor;

import static com.google.common.base.Preconditions.checkNotNull;

class XidContext {
  final long flowVisorXid;
  final long guestXid;
  final GuestConnectionContext guestConnectionContext;

  XidContext(long flowVisorXid, long guestXid, GuestConnectionContext guestConnectionContext) {
    this.flowVisorXid = flowVisorXid;
    this.guestXid = guestXid;
    this.guestConnectionContext = checkNotNull(guestConnectionContext);
  }
}
