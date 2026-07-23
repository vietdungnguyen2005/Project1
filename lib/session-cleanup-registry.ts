type TimerHandle = ReturnType<typeof setInterval> | number;
type CleanupCallback = () => void;

export class SessionCleanupRegistry {
  private timers = new Set<TimerHandle>();
  private abortControllers = new Set<AbortController>();
  private listeners = new Set<CleanupCallback>();
  private cacheFlushers = new Set<CleanupCallback>();
  private flushed = false;

  addTimer(timer: TimerHandle) {
    this.timers.add(timer);
    return timer;
  }

  addAbortController(controller: AbortController) {
    this.abortControllers.add(controller);
    return controller;
  }

  addListener(disconnect: CleanupCallback) {
    this.listeners.add(disconnect);
    return disconnect;
  }

  addCacheFlush(flush: CleanupCallback) {
    this.cacheFlushers.add(flush);
    return flush;
  }

  flush() {
    if (this.flushed) {
      return;
    }

    this.flushed = true;
    this.timers.forEach((timer) => clearInterval(timer));
    this.abortControllers.forEach((controller) => controller.abort());
    this.listeners.forEach((disconnect) => disconnect());
    this.cacheFlushers.forEach((flush) => flush());
    this.timers.clear();
    this.abortControllers.clear();
    this.listeners.clear();
    this.cacheFlushers.clear();
  }
}
