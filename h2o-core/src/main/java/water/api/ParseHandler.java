package water.api;

import water.DKV;
import water.Job;
import water.Key;
import water.fvec.Frame;
import water.fvec.Vec;
import water.parser.ParseDataset;
import water.parser.ParseSetup;

class ParseHandler extends Handler {
  // Entry point for parsing.
  @SuppressWarnings("unused") // called through reflection by RequestServer
  public ParseV2 parse(int version, ParseV2 parse) {
    ParseSetup setup = new ParseSetup(true, 0, 0, null, parse.pType, parse.sep, parse.ncols, parse.singleQuotes, parse.columnNames, parse.domains, null, parse.checkHeader, null);

    Key[] srcs = new Key[parse.srcs.length];
    for (int i = 0; i < parse.srcs.length; i++)
      srcs[i] = parse.srcs[i].key();

    // TODO: add JobBase:
    parse.job = (JobV2)Schema.schema(version, Job.class).fillFromImpl(ParseDataset.parse(parse.hex.key(), srcs, parse.delete_on_done, setup, parse.blocking));
    if( parse.blocking ) {
      Frame fr = DKV.getGet(parse.hex.key());
      parse.rows = fr.numRows();
      if( parse.removeFrame ) {
        parse.vecKeys = fr.keys();
        fr.restructure(new String[0],new Vec[0]);
        fr.delete();
      }
    }
    return parse;
  }
}
