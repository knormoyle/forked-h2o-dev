package water.fvec;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import water.Futures;
import water.TestUtil;

import static org.junit.Assert.assertTrue;
import static water.fvec.Vec.makeCon;
import static water.fvec.Vec.makeSeq;

/** This test tests stability of Vec API. */
public class VecTest extends TestUtil {
  @BeforeClass public static void setup() { stall_till_cloudsize(1); }

  /** Test toEnum call to return correct domain. */
  @Test public void testToEnum() {
    testToEnumDomainMatch(vec(0,1,0,1), ar("0", "1") );
    testToEnumDomainMatch(vec(1,2,3,4,5,6,7), ar("1", "2", "3", "4", "5", "6", "7") );
    testToEnumDomainMatch(vec(0,1,2,99,4,5,6), ar("0", "1", "2", "4", "5", "6", "99") );
  }

  private void testToEnumDomainMatch(Vec f, String[] expectedDomain) {
    Vec ef = null;
    try {
      ef = f.toEnum();
      String[] actualDomain = ef.domain();
      Assert.assertArrayEquals("toEnum call returns wrong domain!", expectedDomain, actualDomain);
    } finally {
      if( f !=null ) f .remove();
      if( ef!=null ) ef.remove();
    }
  }

  @Test public void testMakeConSeq() {
    Vec v;

    v = makeCon(0xCAFE,2*Vec.DFLT_CHUNK_SIZE);
    assertTrue(v.at(234) == 0xCAFE);
    assertTrue(v._espc.length == 3);
    assertTrue(
            v._espc[0] == 0              &&
            v._espc[1] == Vec.DFLT_CHUNK_SIZE
    );
    v.remove(new Futures()).blockForPending();

    v = makeCon(0xCAFE,3*Vec.DFLT_CHUNK_SIZE);
    assertTrue(v.at(234) == 0xCAFE);
    assertTrue(v.at(3*Vec.DFLT_CHUNK_SIZE-1) == 0xCAFE);
    assertTrue(v._espc.length == 4);
    assertTrue(
            v._espc[0] == 0              &&
            v._espc[1] == Vec.DFLT_CHUNK_SIZE   &&
            v._espc[2] == Vec.DFLT_CHUNK_SIZE*2
    );
    v.remove(new Futures()).blockForPending();

    v = makeCon(0xCAFE,3*Vec.DFLT_CHUNK_SIZE+1);
    assertTrue(v.at(234) == 0xCAFE);
    assertTrue(v.at(3*Vec.DFLT_CHUNK_SIZE) == 0xCAFE);
    assertTrue(v._espc.length == 4);
    assertTrue(
            v._espc[0] == 0              &&
            v._espc[1] == Vec.DFLT_CHUNK_SIZE   &&
            v._espc[2] == Vec.DFLT_CHUNK_SIZE*2 &&
            v._espc[3] == Vec.DFLT_CHUNK_SIZE*3+1
    );
    v.remove(new Futures()).blockForPending();

    v = makeCon(0xCAFE,4*Vec.DFLT_CHUNK_SIZE);
    assertTrue(v.at(234) == 0xCAFE);
    assertTrue(v.at(4*Vec.DFLT_CHUNK_SIZE-1) == 0xCAFE);
    assertTrue(v._espc.length == 5);
    assertTrue(
            v._espc[0] == 0              &&
            v._espc[1] == Vec.DFLT_CHUNK_SIZE   &&
            v._espc[2] == Vec.DFLT_CHUNK_SIZE*2 &&
            v._espc[3] == Vec.DFLT_CHUNK_SIZE*3
    );
    v.remove(new Futures()).blockForPending();
  }

  @Test public void testMakeSeq() {
    Vec v = makeSeq(3*Vec.DFLT_CHUNK_SIZE);
    assertTrue(v.at(0) == 1);
    assertTrue(v.at(234) == 235);
    assertTrue(v.at(2*Vec.DFLT_CHUNK_SIZE) == 2*Vec.DFLT_CHUNK_SIZE+1);
    assertTrue(v._espc.length == 4);
    assertTrue(
            v._espc[0] == 0 &&
            v._espc[1] == Vec.DFLT_CHUNK_SIZE &&
            v._espc[2] == Vec.DFLT_CHUNK_SIZE * 2
    );
    v.remove(new Futures()).blockForPending();
  }
}
