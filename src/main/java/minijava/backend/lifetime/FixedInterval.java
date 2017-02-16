package minijava.backend.lifetime;

import minijava.backend.registers.AMD64Register;

public class FixedInterval {
  public final AMD64Register register;
  public final LinearLiveRanges ranges = new LinearLiveRanges();

  public FixedInterval(AMD64Register register) {
    this.register = register;
  }

  public void addDef(BlockPosition position) {
    LiveRange live = ranges.getLiveRangeContaining(position);
    if (live == null) {
      // A write without a later read. Happens for register constraints at Calls. We just assume an interval of
      // length 1.
      live = new LiveRange(position.block, position.pos, position.pos);
    } else {
      ranges.deleteLiveRange(live);
      live = live.from(position.pos);
    }
    ranges.addLiveRange(live);
  }

  public void addUse(BlockPosition position) {
    if (ranges.getLiveRangeContaining(position) == null) {
      // This is a new use for which we haven't yet started an interval
      ranges.addLiveRange(LiveRange.everywhere(position.block).to(position.pos));
      // Register constraints will never reach over block borders, so we should eventually find a definition.
      // This is something we should assert when building fixed intervals!
    }
  }

  @Override
  public String toString() {
    return "FixedInterval{" + "register=" + register + ", ranges=" + ranges + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    FixedInterval that = (FixedInterval) o;

    if (register != that.register) return false;
    return ranges != null ? ranges.equals(that.ranges) : that.ranges == null;
  }

  @Override
  public int hashCode() {
    int result = register != null ? register.hashCode() : 0;
    result = 31 * result + (ranges != null ? ranges.hashCode() : 0);
    return result;
  }
}