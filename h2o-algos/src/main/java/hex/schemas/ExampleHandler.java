package hex.schemas;

import hex.example.Example;
import water.api.Handler;

/** TODO: only used by old-school web ui: remove!  ModelBuilderHander does this for all the algos.  */
@Deprecated
public class ExampleHandler extends Handler {
  public ExampleHandler() {}
  public ExampleV2 train(int version, ExampleV2 s) {
    Example e = s.createAndFillImpl();
    assert e._parms != null;
    e.trainModel();
    return s.fillFromImpl(e);
  }
}
