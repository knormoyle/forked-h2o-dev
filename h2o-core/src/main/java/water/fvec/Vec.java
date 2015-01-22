package water.fvec;

import water.*;
import water.nbhm.NonBlockingHashMapLong;
import water.parser.Categorical;
import water.parser.ParseTime;
import water.parser.ValueString;
import water.util.ArrayUtils;
import water.util.MathUtils;
import water.util.PrettyPrint;
import water.util.UnsafeUtils;
import water.util.Log;

import java.util.Arrays;
import java.util.UUID;

/** A distributed vector/array/column of uniform data.
 *
 *  <p>A distributed vector has a count of elements, an element-to-chunk
 *  mapping, a Java-like type (mostly determines rounding on store and
 *  display), and functions to directly load elements without further
 *  indirections.  The data is compressed, or backed by disk or both.
 *
 *  <p>A Vec is a collection of {@link Chunk}s, each of which holds between 1,000
 *  and 1,000,000 elements.  Operations on a Chunk are intended to be
 *  single-threaded; operations on a Vec are intended to be parallel and
 *  distributed on Chunk granularities, with each Chunk being manipulated by a
 *  separate CPU.  The standard Map/Reduce ({@link MRTask}) paradigm handles
 *  parallel and distributed Chunk access well.
 *
 *  <p>Individual elements can be directly accessed like a (very large and
 *  distributed) array - however this is not the fastest way to access the
 *  data.  Direct access from Chunks is faster, avoiding several layers of
 *  indirection.  In particular accessing a random row from the Vec will force
 *  the containing Chunk data to be cached locally (and network traffic to
 *  bring it local); accessing all rows from a single machine will force all
 *  the Big Data to be pulled local typically resulting in swapping and very
 *  poor performance.  The main API is provided for ease of small-data
 *  manipulations and is fairly slow for writing; when touching ALL the data
 *  you are much better off using e.g. {@link MRTask}.
 *
 *   <p>The main API is {@link #at}, {@link #set}, and {@link #isNA}:<br>
 *   <table class='table table-striped table-bordered' border="1" summary="">
 *   <tr><th>     Returns       </th><th>    Call      </th><th>  Missing?  </th><th>Notes</th>
 *   <tr><td>  {@code double}   </td><td>{@link #at}   </td><td>{@code NaN} </td><td></td>
 *   <tr><td>  {@code long}     </td><td>{@link #at8}  </td><td>   throws   </td><td></td>
 *   <tr><td>  {@code long}     </td><td>{@link #at16l}</td><td>   throws   </td><td>Low  half of 128-bit UUID</td>
 *   <tr><td>  {@code long}     </td><td>{@link #at16h}</td><td>   throws   </td><td>High half of 128-bit UUID</td>
 *   <tr><td>{@link ValueString}</td><td>{@link #atStr}</td><td>{@code null}</td><td>Updates ValueString in-place and returns it for flow-coding</td>
 *   <tr><td>  {@code boolean}  </td><td>{@link #isNA} </td><td>{@code true}</td><td></td>
 *   <tr><td>        </td><td>{@link #set(long,double)}</td><td>{@code NaN} </td><td></td>
 *   <tr><td>        </td><td>{@link #set(long,float)} </td><td>{@code NaN} </td><td>Limited precision takes less memory</td>
 *   <tr><td>        </td><td>{@link #set(long,long)}  </td><td>Cannot set  </td><td></td>
 *   <tr><td>        </td><td>{@link #set(long,String)}</td><td>{@code null}</td><td>Convenience wrapper for String</td>
 *   <tr><td>        </td><td>{@link #setNA(long)}     </td><td>            </td><td></td>
 *   </table>
 *
 *  <p>Example manipulating some individual elements:<pre>
 *    double r1 = vec.at(0x123456789L);  // Access element 0x1234567889 as a double
 *    double r2 = vec.at(-1);            // Throws AIOOBE
 *    long   r3 = vec.at8_abs(1);            // Element #1, as a long
 *    vec.set(2,r1+r3);                  // Set element #2, as a double
 *  </pre>
 *
 *  <p>Vecs have a loosely enforced <em>type</em>: one of numeric, {@link UUID}
 *  or {@link String}.  Numeric types are further broken down into integral
 *  ({@code long}) and real ({@code double}) types.  The {@code enum} type is
 *  an integral type, with a String mapping side-array.  Most of the math
 *  algorithms will treat enums as small dense integers, and most enum
 *  printouts will use the String mapping.  Time is another special integral
 *  type: it is represented as milliseconds since the unix epoch, and is mostly
 *  treated as an integral type when doing math but it has special time-based
 *  printout formatting.  All types support the notion of a missing element; for
 *  real types this is always NaN.  It is an error to attempt to fetch a
 *  missing integral type, and {@link #isNA} must be called first.  Integral
 *  types are losslessly compressed.  Real types may lose 1 or 2 ULPS due to
 *  compression.
 *
 *  <p>Reading elements as doubles, or checking for an element missing is
 *  always safe.  Reading a missing integral type throws an exception, since
 *  there is no NaN equivalent in the integer domain.  <em>Writing</em> to
 *  elements may throw if the backing data is read-only (file backed), and
 *  otherwise is fully supported.
 *
 *  <p>Note this dangerous scenario: loading a missing value as a double, and
 *  setting it as a long: <pre>
 *   set(row,(long)at(row)); // Danger!
 *</pre>
 *  The cast from a Double.NaN to a long produces a zero!  This code will
 *  silently replace a missing value with a zero.
 *
 *  <p>Vecs have a lazily computed {@link RollupStats} object and Key.  The
 *  RollupStats give fast access to the common metrics: {@link #min}, {@link
 *  #max}, {@link #mean}, {@link #sigma}, the count of missing elements ({@link
 *  #naCnt}) and non-zeros ({@link #nzCnt}), amongst other stats.  They are
 *  cleared if the Vec is modified and lazily recomputed after the modified Vec
 *  is closed.  Clearing the RollupStats cache is fairly expensive for
 *  individual {@link #set} calls but is easy to amortize over a large count of
 *  writes; i.e., batch writing is efficient.  This is normally handled by the
 *  MRTask framework; the {@link Vec.Writer} framework allows
 *  <em>single-threaded</em> efficient batch writing for smaller Vecs.
 *
 *  <p>Example usage of common stats:<pre>
 *    double mean = vec.mean();  // Vec's mean; first touch computes and caches rollups
 *    double min  = vec.min();   // Smallest element; already computed
 *    double max  = vec.max();   // Largest element; already computed
 *    double sigma= vec.sigma(); // Standard deviation; already computed
 *  </pre>
 *
 *  <p>Example: Impute (replace) missing values with the mean.  Note that the
 *  use of {@code vec.mean()} in the constructor uses (and computes) the
 *  general RollupStats before the MRTask starts.  Setting a value in the Chunk
 *  clears the RollupStats (since setting any value but the mean will change
 *  the mean); they will be recomputed at the next use after the MRTask.
 *  <pre>
 *    new MRTask{} { final double _mean = vec.mean();
 *      public void map( Chunk chk ) {
 *        for( int row=0; row &lt; chk._len; row++ )
 *          if( chk.isNA(row) ) chk.set(row,_mean);
 *      }
 *    }.doAll(vec);
 *  </pre>
 *
 *  <p>Vecs have a {@link Vec.VectorGroup}.  Vecs in the same VectorGroup have the
 *  same Chunk and row alignment - that is, Chunks with the same index are
 *  homed to the same Node and have the same count of rows-per-Chunk.  {@link
 *  Frame}s are only composed of Vecs of the same VectorGroup (or very small
 *  Vecs) guaranteeing that all elements of each row are homed to the same Node
 *  and set of Chunks - such that a simple {@code for} loop over a set of
 *  Chunks all operates locally.  See the example in the {@link Chunk} class.
 *
 *  <p>It is common and cheap to make new Vecs in the same VectorGroup as an
 *  existing Vec and initialized to e.g. zero.  Such Vecs are often used as
 *  temps, and usually immediately set to interest values in a later MRTask
 *  pass.
 * 
 *  <p>Example creation of temp Vecs:<pre>
 *    Vec tmp0 = vec.makeZero();         // New Vec with same VectorGroup and layout as vec, filled with zero
 *    Vec tmp1 = vec.makeCon(mean);      // Filled with 'mean'
 *    assert tmp1.at(0x123456789)==mean; // All elements filled with 'mean'
 *    for( int i=0; i&lt;100; i++ )         // A little math on the first 100 elements
 *      tmp0.set(i,tmp1.at(i)+17);       // ...set into the tmp0 vec
 *  </pre>
 *
 *  <p>Vec {@link Key}s have a special layout (enforced by the various Vec
 *  constructors) so there is a direct Key mapping from a Vec to a numbered
 *  Chunk and vice-versa.  This mapping is crucially used in all sorts of
 *  places, basically representing a global naming scheme across a Vec and the
 *  Chunks that make it up.  The basic layout created by {@link #newKey}:
 * <pre>
 *              byte:    0      1   2 3 4 5  6 7 8 9  10+
 *  Vec   Key layout: Key.VEC  -1   vec#grp    -1     normal Key bytes; often e.g. a function of original file name
 *  Chunk Key layout: Key.CHK  -1   vec#grp  chunk#   normal Key bytes; often e.g. a function of original file name
 *  Group Key layout: Key.GRP  -1     -1       -1     normal Key bytes; often e.g. a function of original file name
 *  RollupStats Key : Key.CHK  -1   vec#grp    -2     normal Key bytes; often e.g. a function of original file name
 * </pre>
 *
 * @author Cliff Click
 */
public class Vec extends Keyed {
  /** Log-2 of Chunk size. */
  public static final int DFLT_LOG2_CHUNK_SIZE = 20/*1Meg*/+2/*4Meg*/;
  protected int _log2ChkSize = DFLT_LOG2_CHUNK_SIZE;
  /** Default Chunk size in bytes, useful when breaking up large arrays into
   *  "bite-sized" chunks.  Bigger increases batch sizes, lowers overhead
   *  costs, lower increases fine-grained parallelism. */
  public static final int DFLT_CHUNK_SIZE = 1 << DFLT_LOG2_CHUNK_SIZE;
  protected int _chunkSize = DFLT_CHUNK_SIZE;
  public void setChunkSize(int chunkSize) {
    if (chunkSize <= 0) throw new IllegalArgumentException("Chunk sizes must be > 0.");
    if (chunkSize > (1<<30) ) throw new IllegalArgumentException("Chunk sizes must be < 1G.");
    _log2ChkSize = water.util.MathUtils.log2(chunkSize);
    _chunkSize = 1 << _log2ChkSize;
    if (_chunkSize != chunkSize) Log.info("Provided chunk size " + chunkSize +
            " is not a power of 2.  Using " + _chunkSize + " instead.");
  }
  public int getChunkSize() {return _chunkSize;}

  /** Element-start per chunk.  Always zero for chunk 0.  One more entry than
   *  chunks, so the last entry is the total number of rows.  This field is
   *  dead/ignored in subclasses that are guaranteed to have fixed-sized chunks
   *  such as file-backed Vecs. */
  final public long[] _espc;

  private String [] _domain;
  /** Returns the enum toString mapping array, or null if not an Categorical column.
   *  Not a defensive clone (to expensive to clone; coding error to change the
   *  contents).
   *  @return the enum / factor / categorical mapping array, or null if not a Categorical column */
  public final String[] domain() { return _domain; }
  /** Returns the {@code i}th factor for this enum column.
   *  @return The {@code i}th factor */
  public final String factor( long i ) { return _domain[(int)i]; }
  /** Set the Categorical/factor/categorical names.  No range-checking on the actual
   *  underlying numeric domain; user is responsible for maintaining a mapping
   *  which is coherent with the Vec contents. */
  public final void setDomain(String[] domain) { _domain = domain; if( domain != null ) _type = T_ENUM; }
  /** Returns cardinality for enum domain or -1 for other types. */
  public final int cardinality() { return isEnum() ? _domain.length : -1; }

  // Vec internal type
  public static final byte T_BAD  =  0; // No none-NA rows (triple negative! all NAs or zero rows)
  public static final byte T_UUID =  1; // UUID
  public static final byte T_STR  =  2; // String
  public static final byte T_NUM  =  3; // Numeric, but not enum or time
  public static final byte T_ENUM =  4; // Integer, with a enum/factor String mapping
  public static final byte T_TIME =  5; // Long msec since the Unix Epoch - with a variety of display/parse options
  public static final byte T_TIMELAST= (byte)(T_TIME+ParseTime.TIME_PARSE.length);
  byte _type;                   // Vec Type

  /** True if this is an Categorical column.  All enum columns are also {@link #isInt}, but
   *  not vice-versa.
   *  @return true if this is an Categorical column.  */
  public final boolean isEnum() { 
    assert (_type==T_ENUM && _domain!=null) || (_type!=T_ENUM && _domain==null); 
    return _type==T_ENUM; 
  }
  /** True if this is a UUID column.  
   *  @return true if this is a UUID column.  */
  public final boolean isUUID   (){ return _type==T_UUID; }
  /** True if this is a String column.  
   *  @return true if this is a String column.  */
  public final boolean isString (){ return _type==T_STR; }
  /** True if this is a numeric column, excluding enum and time types.
   *  @return true if this is a numeric column, excluding enum and time types  */
  public final boolean isNumeric(){ return _type==T_NUM; }
  /** True if this is a time column.  All time columns are also {@link #isInt}, but
   *  not vice-versa.
   *  @return true if this is a time column.  */
  public final boolean isTime   (){ return _type>=T_TIME && _type<T_TIMELAST; }
//  final byte timeMode(){ assert isTime(); return (byte)(_type-T_TIME); }
  /** Time formatting string.
   *  @return Time formatting string */
//  public final String timeParse(){ return ParseTime.TIME_PARSE[timeMode()]; }


  /** Build a numeric-type Vec; the caller understands Chunk layout (via the
   *  {@code espc} array). */
  public Vec( Key key, long espc[]) { this(key, espc, null, T_NUM); }

  /** Build a numeric-type or enum-type Vec; the caller understands Chunk
   *  layout (via the {@code espc} array); enum Vecs need to pass the
   *  domain. */
  Vec( Key key, long espc[], String[] domain) { this(key,espc,domain, (domain==null?T_NUM:T_ENUM)); }

  public Vec( Key key, long espc[], String[] domain, byte type ) { this(key, espc, domain, type, DFLT_CHUNK_SIZE); }

  /** Main default constructor; the caller understands Chunk layout (via the
   *  {@code espc} array), plus enum/factor the {@code domain} (or null for
   *  non-enums), and the Vec type. */
  public Vec( Key key, long espc[], String[] domain, byte type, int chunkSize ) {
    super(key);
    assert key._kb[0]==Key.VEC;
    assert domain==null || type==T_ENUM;
    assert T_BAD <= type && type < T_TIMELAST; // Note that T_BAD is allowed for all-NA Vecs
    _type = type;
    _espc = espc;
    _domain = domain;
    _chunkSize = chunkSize;
  }

  /** Number of elements in the vector; returned as a {@code long} instead of
   *  an {@code int} because Vecs support more than 2^32 elements. Overridden
   *  by subclasses that compute length in an alternative way, such as
   *  file-backed Vecs.
   *  @return Number of elements in the vector */
  public long length() { return _espc[_espc.length-1]; }

  /** Number of chunks, returned as an {@code int} - Chunk count is limited by
   *  the max size of a Java {@code long[]}.  Overridden by subclasses that
   *  compute chunks in an alternative way, such as file-backed Vecs.
   *  @return Number of chunks */
  public int nChunks() { return _espc.length-1; }

  /** Convert a chunk-index into a starting row #.  For constant-sized chunks
   *  this is a little shift-and-add math.  For variable-sized chunks this is a
   *  table lookup. */
  long chunk2StartElem( int cidx ) { return _espc[cidx]; }

  /** Number of rows in chunk. Does not fetch chunk content. */
  private int chunkLen( int cidx ) { return (int) (_espc[cidx + 1] - _espc[cidx]); }

  /** Check that row-layouts are compatible. */
  boolean checkCompatible( Vec v ) {
    // Groups are equal?  Then only check length
    if( group().equals(v.group()) ) return length()==v.length();
    // Otherwise actual layout has to be the same, and size "small enough"
    // to not worry about data-replication when things are not homed the same.
    // The layout test includes an exactly-equals-length check.
    return Arrays.equals(_espc,v._espc) && length() < 1e5;
  }

  /** Default read/write behavior for Vecs.  File-backed Vecs are read-only. */
  boolean readable() { return true ; }
  /** Default read/write behavior for Vecs.  AppendableVecs are write-only. */
  boolean writable() { return true; }
  /** Get the _espc long[]. */
  public long[] get_espc() { return _espc; }
  /** Get the column type. */
  public byte get_type() { return _type; }
  
  // ======= Create zero/constant Vecs ======

  /** Make a new zero-filled vector with the given row count. 
   *  @return New zero-filled vector with the given row count. */
  public static Vec makeZero( long len ) { return makeCon(0L,len); }

  /** Make a new constant vector with the given row count. 
   *  @return New constant vector with the given row count. */
  public static Vec makeCon(double x, long len) {
    int log_rows_per_chunk = DFLT_LOG2_CHUNK_SIZE;
    return makeCon(x, len, log_rows_per_chunk);
  }

  /** Make a new constant vector with the given row count.
   *  @return New constant vector with the given row count. */
  public static Vec makeCon(double x, long len, int log_rows_per_chunk) {
    int nchunks = (int)Math.max(1,len >> log_rows_per_chunk);
    long[] espc = new long[nchunks+1];
    for( int i=0; i<nchunks; i++ )
      espc[i] = ((long)i)<<log_rows_per_chunk;
    espc[nchunks] = len;
    return makeCon(x,VectorGroup.VG_LEN1,espc);
  }

  /** Make a new vector with the same size and data layout as the current one,
   *  and initialized to zero.
   *  @return A new vector with the same size and data layout as the current one,
   *  and initialized to zero.  */
  public Vec makeZero() { return makeCon(0, null, group(), _espc); }

  /** A new vector with the same size and data layout as the current one, and
   *  initialized to zero, with the given enum domain.
   *  @return A new vector with the same size and data layout as the current
   *  one, and initialized to zero, with the given enum domain. */
  public Vec makeZero(String[] domain) { return makeCon(0, domain, group(), _espc); }

  /**
   * A new vector which is a copy of {@code this} one.
   * @return a copy of the vector.
   */
  public Vec makeCopy(){
    final Vec v = new Vec(group().addVec(),_espc.clone());
    new MRTask(){
      @Override public void map(Chunk c){
        Chunk c2 = (Chunk)c.clone();
        c2._mem = c2._mem.clone();
        DKV.put(v.chunkKey(c.cidx()),c2,_fs);
      }
    }.doAll(this);
    DKV.put(v._key,v);
    return v;
  }

  private static Vec makeCon( final long l, String[] domain, VectorGroup group, long[] espc ) {
    final int nchunks = espc.length-1;
    final Vec v0 = new Vec(group.addVec(), espc, domain);
    new MRTask() {              // Body of all zero chunks
      @Override protected void setupLocal() {
        for( int i=0; i<nchunks; i++ ) {
          Key k = v0.chunkKey(i);
          if( k.home() ) DKV.put(k,new C0LChunk(l,v0.chunkLen(i)),_fs);
        }
      }
    }.doAllNodes();
    DKV.put(v0._key,v0);        // Header last
    return v0;
  }

  public static Vec makeVec(double [] vals, Key vecKey){
    long [] espc = new long[2];
    espc[1] = vals.length;
    Vec v = new Vec(vecKey,espc);
    NewChunk nc = new NewChunk(v,0);
    Futures fs = new Futures();
    for(double d:vals)
      nc.addNum(d);
    nc.close(fs);
    DKV.put(v._key,v,fs);
    fs.blockForPending();
    return v;
  }

  /**
   * Calculates safe and hopefully optimal chunk sizes.  Four cases
   * exist.
   * <p>
   * very small data < 128K per proc - uses default chunk size and
   * all data will be in one chunk
   * <p>
   * small data - data is partitioned into chunks that at least
   * 4 chunks per core to help keep all cores loaded
   * <p>
   * default - chunks are {@value #DFLT_CHUNK_SIZE}
   * <p>
   * large data - if the data would create more than 4M keys per
   * node, then chunk sizes larger than DFLT_CHUNK_SIZE are issued.
   * <p>
   * Too many keys can create enough overhead to blow out memory in
   * large data parsing. # keys = (parseSize / chunkSize) * numCols.
   * Key limit of 2M is a guessed "reasonable" number.
   *
   * @param totalSize - parse size in bytes (across all files to be parsed)
   * @param numCols - number of columns expected in dataset
   * @return - optimal chunk size in bytes (always a power of 2).
   */
  public static int calcOptimalChunkSize(long totalSize, int numCols) {
    long localParseSize =  totalSize / H2O.getCloudSize();
    int chunkSize = (int) (localParseSize /
            (Runtime.getRuntime().availableProcessors() * 4));

    // Super small data check - less than 32K/thread
    if (chunkSize <= (1 << 15)) {
      return DFLT_CHUNK_SIZE;
    }

    // Small data check
    chunkSize = 1 << MathUtils.log2(chunkSize); //closest power of 2
    if (chunkSize < DFLT_CHUNK_SIZE) {
      return chunkSize;
    }

    // Big data check
    long tmp = (localParseSize * numCols / (1 << 21)); // ~ 2M keys per node
    if (tmp > (1 << 30)) return (1 << 30); // Max limit is 1G
    if (tmp > DFLT_CHUNK_SIZE) {
      chunkSize = 1 << MathUtils.log2((int) tmp); //closest power of 2
      return chunkSize;
    } else return DFLT_CHUNK_SIZE;
  }

  /** Make a new vector with the same size and data layout as the current one,
   *  and initialized to the given constant value.
   *  @return A new vector with the same size and data layout as the current one,
   *  and initialized to the given constant value.  */
  public Vec makeCon( final double d ) { return makeCon(d,group(),_espc); }

  private static Vec makeCon( final double d, VectorGroup group, long[] espc ) {
    if( (long)d==d ) return makeCon((long)d, null, group, espc);
    final int nchunks = espc.length-1;
    final Vec v0 = new Vec(group.addVec(), espc, null, T_NUM);
    new MRTask() {              // Body of all zero chunks
      @Override protected void setupLocal() {
        for( int i=0; i<nchunks; i++ ) {
          Key k = v0.chunkKey(i);
          if( k.home() ) DKV.put(k,new C0DChunk(d,v0.chunkLen(i)),_fs);
        }
      }
    }.doAllNodes();
    DKV.put(v0._key,v0);        // Header last
    return v0;
  }


  public Vec [] makeZeros(int n){return makeZeros(n,null,null);}
  public Vec [] makeZeros(int n, String [][] domain, byte[] types){ return makeCons(n, 0, domain, types);}

  // Make a bunch of compatible zero Vectors
  public Vec[] makeCons(int n, final long l, String[][] domains, byte[] types) {
    final int nchunks = nChunks();
    Key[] keys = group().addVecs(n);
    final Vec[] vs = new Vec[keys.length];
    for(int i = 0; i < vs.length; ++i)
      vs[i] = new Vec(keys[i],_espc, 
                      domains== null ? null : domains[i], 
                      types  == null ? T_NUM: types[i]);
    new MRTask() {
      @Override protected void setupLocal() {
        for (Vec v1 : vs) {
          for (int i = 0; i < nchunks; i++) {
            Key k = v1.chunkKey(i);
            if (k.home()) DKV.put(k, new C0LChunk(l, chunkLen(i)), _fs);
          }
        }
        for( Vec v : vs ) if( v._key.home() ) DKV.put(v._key,v,_fs);
      }
    }.doAllNodes();
    return vs;
  }

  /** Make a new vector initialized to increasing integers, starting with 1.
   *  @return A new vector initialized to increasing integers, starting with 1. */
  public static Vec makeSeq( long len) {
    return new MRTask() {
      @Override public void map(Chunk[] cs) {
        for( Chunk c : cs )
          for( int r = 0; r < c._len; r++ )
            c.set(r, r + 1 + c._start);
      }
    }.doAll(makeZero(len))._fr.vecs()[0];
  }

  /** Make a new vector initialized to increasing integers, starting with `min`.
   *  @return A new vector initialized to increasing integers, starting with `min`.
   */
  public static Vec makeSeq(final long min, long len) {
    return new MRTask() {
      @Override public void map(Chunk[] cs) {
        for (Chunk c : cs)
          for (int r = 0; r < c._len; r++)
            c.set(r, r + min + c._start);
      }
    }.doAll(makeZero(len))._fr.vecs()[0];
  }

  /** Make a new vector initialized to increasing integers mod {@code repeat}, starting with 1.
   *  @return A new vector initialized to increasing integers mod {@code repeat}, starting
   *  with 1. */
  public static Vec makeRepSeq( long len, final long repeat ) {
    return new MRTask() {
      @Override public void map(Chunk[] cs) {
        for( Chunk c : cs )
          for( int r = 0; r < c._len; r++ )
            c.set(r, (r + 1 + c._start) % repeat);
      }
    }.doAll(makeZero(len))._fr.vecs()[0];
  }


  // ======= Rollup Stats ======

  /** Vec's minimum value 
   *  @return Vec's minimum value */
  public double min()  { return mins()[0]; }
  /** Vec's 5 smallest values 
   *  @return Vec's 5 smallest values */
  public double[]mins(){ return rollupStats()._mins; }
  /** Vec's maximum value 
   *  @return Vec's maximum value */
  public double max()  { return maxs()[0]; }
  /** Vec's 5 largest values 
   *  @return Vec's 5 largeest values */
  public double[]maxs(){ return rollupStats()._maxs; }
  /** True if the column contains only a constant value and it is not full of NAs 
   *  @return True if the column is constant */
  public final boolean isConst() { return min() == max(); }
  /** True if the column contains only NAs
   *  @return True if the column contains only NAs */
  public final boolean isBad() { return naCnt()==length(); }
  /** Vecs's mean 
   *  @return Vec's mean */
  public double mean() { return rollupStats()._mean; }
  /** Vecs's standard deviation
   *  @return Vec's standard deviation */
  public double sigma(){ return rollupStats()._sigma; }
  /** Count of missing elements
   *  @return Count of missing elements */
  public long  naCnt() { return rollupStats()._naCnt; }
  /** Count of non-zero elements
   *  @return Count of non-zero elements */
  public long  nzCnt() { return rollupStats()._nzCnt; }
  /** Count of positive infinities
   *  @return Count of positive infinities */
  public long  pinfs() { return rollupStats()._pinfs; }
  /** Count of negative infinities
   *  @return Count of negative infinities */
  public long  ninfs() { return rollupStats()._ninfs; }
  /** <b>isInt</b> is a property of numeric Vecs and not a type; this
   *  property can be changed by assigning non-integer values into the Vec (or
   *  restored by overwriting non-integer values with integers).  This is a
   *  strong type for {@link #isEnum} and {@link #isTime} Vecs.
   *  @return true if the Vec is all integers */
  public boolean isInt(){return rollupStats()._isInt; }
  /** Size of compressed vector data. */
  long byteSize(){return rollupStats()._size; }

  /** Default Histogram bins. */
  public static final double PERCENTILES[] = {0.01,0.10,0.25,1.0/3.0,0.50,2.0/3.0,0.75,0.90,0.99};
  /** A simple and cheap histogram of the Vec, useful for getting a broad
   *  overview of the data.  Each bin is row-counts for the bin's range.  The
   *  bin's range is computed from {@link #base} and {@link #stride}.  The
   *  histogram is computed on first use and cached thereafter.
   *  @return A set of histogram bins. */
  public long[] bins() { return RollupStats.get(this,true)._bins;      }
  /** Optimistically return the histogram bins, or null if not computed 
   *  @return the histogram bins, or null if not computed */
  public long[] lazy_bins() { return rollupStats()._bins; }
  /** The {@code base} for a simple and cheap histogram of the Vec, useful
   *  for getting a broad overview of the data.  This returns the base of
   *  {@code bins()[0]}.
   *  @return the base of {@code bins()[0]} */
  public double base()      { return RollupStats.get(this,true).h_base(); }
  /** The {@code stride} for a a simple and cheap histogram of the Vec, useful
   *  for getting a broad overview of the data.  This returns the stride
   *  between any two bins.
   *  @return the stride between any two bins */
  public double stride()    { return RollupStats.get(this,true).h_stride(); }

  /** A simple and cheap percentiles of the Vec, useful for getting a broad
   *  overview of the data.  The specific percentiles are take from {@link #PERCENTILES}. 
   *  @return A set of percentiles */
  public double[] pctiles() { return RollupStats.get(this, true)._pctiles;   }


  /** Compute the roll-up stats as-needed */
  private RollupStats rollupStats() { return RollupStats.get(this); }

  public void startRollupStats(Futures fs) { startRollupStats(fs,false);}

  /**
   * Check if we have local cached copy of basic Vec stats (including histogram if requested) and if not start task to compute and fetch them;
   * useful when launching a bunch of them in parallel to avoid single threaded execution later (e.g. for-loop accessing min/max of every vec in a frame).
   *
   * Does *not* guarantee rollup stats will be cached locally when done since there may be racy changes to vec which will flush local caches.
   *
   * @param fs Futures allow to wait for this task to finish.
   * @param doHisto Also compute histogram, requires second pass over data amd is not computed by default.
   *
   */
  public void startRollupStats(Futures fs, boolean doHisto) { RollupStats.start(this,fs,doHisto); }

  /** A high-quality 64-bit checksum of the Vec's content, useful for
   *  establishing dataset identity.
   *  @return Checksum of the Vec's content  */
  @Override protected long checksum_impl() { return rollupStats()._checksum;}


  /** Begin writing into this Vec.  Immediately clears all the rollup stats
   *  ({@link #min}, {@link #max}, {@link #mean}, etc) since such values are
   *  not meaningful while the Vec is being actively modified.  Can be called
   *  repeatedly.  Per-chunk row-counts will not be changing, just row
   *  contents. */
  public void preWriting( ) {
    if( !writable() ) throw new IllegalArgumentException("Vector not writable");
    final Key rskey = rollupStatsKey();
    Value val = DKV.get(rskey);
    if( val != null ) {
      RollupStats rs = val.get(RollupStats.class);
      if( rs.isMutating() ) return; // Vector already locked against rollups
    }
    // Set rollups to "vector isMutating" atomically.
    new TAtomic<RollupStats>() {
      @Override protected RollupStats atomic(RollupStats rs) {
        return rs != null && rs.isMutating() ? null : RollupStats.makeMutating(rskey);
      }
    }.invoke(rskey);
  }

  /** Stop writing into this Vec.  Rollup stats will again (lazily) be
   *  computed. */
  public Futures postWrite( Futures fs ) {
    // Get the latest rollups *directly* (do not compute them!).
    final Key rskey = rollupStatsKey();
    Value val = DKV.get(rollupStatsKey());
    if( val != null ) {
      RollupStats rs = val.get(RollupStats.class);
      if( rs.isMutating() )  // Vector was mutating, is now allowed for rollups
        DKV.remove(rskey,fs);// Removing will cause them to be rebuilt, on demand
    }
    return fs;                  // Flow-coding
  }


  // ======= Key and Chunk Management ======

  /** Convert a row# to a chunk#.  For constant-sized chunks this is a little
   *  shift-and-add math.  For variable-sized chunks this is a binary search,
   *  with a sane API (JDK has an insane API).  Overridden by subclasses that
   *  compute chunks in an alternative way, such as file-backed Vecs. */
   int elem2ChunkIdx( long i ) {
    if( !(0 <= i && i < length()) ) throw new ArrayIndexOutOfBoundsException("0 <= "+i+" < "+length());
    int lo=0, hi = nChunks();
    while( lo < hi-1 ) {
      int mid = (hi+lo)>>>1;
      if( i < _espc[mid] ) hi = mid;
      else                 lo = mid;
    }
    while( _espc[lo+1] == i ) lo++;
    return lo;
  }

  /** Get a Vec Key from Chunk Key, without loading the Chunk.
   *  @return the Vec Key for the Chunk Key */
  public static Key getVecKey( Key chk_key ) {
    assert chk_key._kb[0]==Key.CHK;
    byte [] bits = chk_key._kb.clone();
    bits[0] = Key.VEC;
    UnsafeUtils.set4(bits, 6, -1); // chunk#
    return Key.make(bits);
  }

  /** Get a Chunk Key from a chunk-index.  Basically the index-to-key map.
   *  @return Chunk Key from a chunk-index */
  public Key chunkKey(int cidx ) { return chunkKey(_key,cidx); }

  /** Get a Chunk Key from a chunk-index and a Vec Key, without needing the
   *  actual Vec object.  Basically the index-to-key map.
   *  @return Chunk Key from a chunk-index and Vec Key */
  public static Key chunkKey(Key veckey, int cidx ) {
    byte [] bits = veckey._kb.clone();
    bits[0] = Key.CHK;
    UnsafeUtils.set4(bits,6,cidx); // chunk#
    return Key.make(bits);
  }
  Key rollupStatsKey() { return chunkKey(-2); }

  /** Get a Chunk's Value by index.  Basically the index-to-key map, plus the
   *  {@code DKV.get()}.  Warning: this pulls the data locally; using this call
   *  on every Chunk index on the same node will probably trigger an OOM!  */
  Value chunkIdx( int cidx ) {
    Value val = DKV.get(chunkKey(cidx));
    assert checkMissing(cidx,val);
    return val;
  }

  private boolean checkMissing(int cidx, Value val) {
    if( val != null ) return true;
    System.out.println("Error: Missing chunk "+cidx+" for "+_key);
    return false;
  }

  /** Return the next Chunk, or null if at end.  Mostly useful for parsers or
   *  optimized stencil calculations that want to "roll off the end" of a
   *  Chunk, but in a highly optimized way. */
  Chunk nextChunk( Chunk prior ) {
    int cidx = elem2ChunkIdx(prior._start)+1;
    return cidx < nChunks() ? chunkForChunkIdx(cidx) : null;
  }

  /** Make a new random Key that fits the requirements for a Vec key. 
   *  @return A new random Vec Key */
  public static Key newKey(){return newKey(Key.make());}

  /** Internally used to help build Vec and Chunk Keys; public to help
   *  PersistNFS build file mappings.  Not intended as a public field. */
  public static final int KEY_PREFIX_LEN = 4+4+1+1;
  /** Make a new Key that fits the requirements for a Vec key, based on the
   *  passed-in key.  Used to make Vecs that back over e.g. disk files. */
  static Key newKey(Key k) {
    byte [] kb = k._kb;
    byte [] bits = MemoryManager.malloc1(kb.length+KEY_PREFIX_LEN);
    bits[0] = Key.VEC;
    bits[1] = -1;         // Not homed
    UnsafeUtils.set4(bits,2,0);   // new group, so we're the first vector
    UnsafeUtils.set4(bits,6,-1);  // 0xFFFFFFFF in the chunk# area
    System.arraycopy(kb, 0, bits, 4+4+1+1, kb.length);
    return Key.make(bits);
  }

  /** Make a Vector-group key.  */
  private Key groupKey(){
    byte [] bits = _key._kb.clone();
    bits[0] = Key.GRP;
    UnsafeUtils.set4(bits, 2, -1);
    UnsafeUtils.set4(bits, 6, -1);
    return Key.make(bits);
  }

  /** Get the group this vector belongs to.  In case of a group with only one
   *  vector, the object actually does not exist in KV store.
   *  @return VectorGroup this vector belongs to */
  public final VectorGroup group() {
    Key gKey = groupKey();
    Value v = DKV.get(gKey);
    // if no group exists we have to create one
    return v==null ? new VectorGroup(gKey,1) : (VectorGroup)v.get();
  }

  /** The Chunk for a chunk#.  Warning: this pulls the data locally; using this
   *  call on every Chunk index on the same node will probably trigger an OOM!
   *  @return Chunk for a chunk# */
  public Chunk chunkForChunkIdx(int cidx) {
    long start = chunk2StartElem(cidx); // Chunk# to chunk starting element#
    Value dvec = chunkIdx(cidx);        // Chunk# to chunk data
    Chunk c = dvec.get();               // Chunk data to compression wrapper
    long cstart = c._start;             // Read once, since racily filled in
    Vec v = c._vec;
    if( cstart == start && v != null) return c;     // Already filled-in
    assert cstart == -1 || v == null;       // Was not filled in (everybody racily writes the same start value)
    c._vec = this;             // Fields not filled in by unpacking from Value
    c._start = start;          // Fields not filled in by unpacking from Value
    return c;
  }

  /** The Chunk for a row#.  Warning: this loads the data locally!  */
  private Chunk chunkForRow_impl(long i) { return chunkForChunkIdx(elem2ChunkIdx(i)); }

  // Cache of last Chunk accessed via at/set api
  transient Chunk _cache;

  /** The Chunk for a row#.  Warning: this pulls the data locally; using this
   *  call on every Chunk index on the same node will probably trigger an OOM!
   *  @return Chunk for a row# */
  public final Chunk chunkForRow(long i) {
    Chunk c = _cache;
    return (c != null && c.chk2()==null && c._start <= i && i < c._start+ c._len) ? c : (_cache = chunkForRow_impl(i));
  }

  // ======= Direct Data Accessors ======

  /** Fetch element the slow way, as a long.  Floating point values are
   *  silently rounded to an integer.  Throws if the value is missing. 
   *  @return {@code i}th element as a long, or throw if missing */
  public final long  at8( long i ) { return chunkForRow(i).at8_abs(i); }

  /** Fetch element the slow way, as a double, or Double.NaN is missing.
   *  @return {@code i}th element as a double, or Double.NaN if missing */
  public final double at( long i ) { return chunkForRow(i).at_abs(i); }
  /** Fetch the missing-status the slow way. 
   *  @return the missing-status the slow way */
  public final boolean isNA(long row){ return chunkForRow(row).isNA_abs(row); }

  /** Fetch element the slow way, as the low half of a UUID.  Throws if the
   *  value is missing or not a UUID.
   *  @return {@code i}th element as a UUID low half, or throw if missing */
  public final long  at16l( long i ) { return chunkForRow(i).at16l_abs(i); }
  /** Fetch element the slow way, as the high half of a UUID.  Throws if the
   *  value is missing or not a UUID.
   *  @return {@code i}th element as a UUID high half, or throw if missing */
  public final long  at16h( long i ) { return chunkForRow(i).at16h_abs(i); }

  /** Fetch element the slow way, as a {@link ValueString} or null if missing.
   *  Throws if the value is not a String.  ValueStrings are String-like
   *  objects than can be reused in-place, which is much more efficient than
   *  constructing Strings.
   *  @return {@code i}th element as {@link ValueString} or null if missing, or
   *  throw if not a String */
  public final ValueString atStr( ValueString vstr, long i ) { return chunkForRow(i).atStr_abs(vstr, i); }

  /** Write element the slow way, as a long.  There is no way to write a
   *  missing value with this call.  Under rare circumstances this can throw:
   *  if the long does not fit in a double (value is larger magnitude than
   *  2^52), AND float values are stored in Vec.  In this case, there is no
   *  common compatible data representation.  */
  public final void set( long i, long l) {
    Chunk ck = chunkForRow(i);
    ck.set_abs(i, l);
    postWrite(ck.close(ck.cidx(), new Futures())).blockForPending();
  }

  /** Write element the slow way, as a double.  Double.NaN will be treated as a
   *  set of a missing element. */
  public final void set( long i, double d) {
    Chunk ck = chunkForRow(i);
    ck.set_abs(i, d);
    postWrite(ck.close(ck.cidx(), new Futures())).blockForPending();
  }

  /** Write element the slow way, as a float.  Float.NaN will be treated as a
   *  set of a missing element. */
  public final void set( long i, float  f) {
    Chunk ck = chunkForRow(i);
    ck.set_abs(i, f);
    postWrite(ck.close(ck.cidx(), new Futures())).blockForPending();
  }

  /** Set the element as missing the slow way.  */
  final void setNA( long i ) {
    Chunk ck = chunkForRow(i);
    ck.setNA_abs(i);
    postWrite(ck.close(ck.cidx(), new Futures())).blockForPending();
  }

  /** Write element the slow way, as a String.  {@code null} will be treated as a
   *  set of a missing element. */
  public final void set( long i, String str) {
    Chunk ck = chunkForRow(i);
    ck.set_abs(i, str);
    postWrite(ck.close(ck.cidx(), new Futures())).blockForPending();
  }

  /** A more efficient way to write randomly to a Vec - still single-threaded,
   *  still slow, but much faster than Vec.set().  Limited to single-threaded
   *  single-machine writes.
   *
   * Usage:
   * Vec.Writer vw = vec.open();
   * vw.set(0, 3.32);
   * vw.set(1, 4.32);
   * vw.set(2, 5.32);
   * vw.close();
   */
  public final static class Writer implements java.io.Closeable {
    final Vec _vec;
    private Writer(Vec v) { (_vec=v).preWriting(); }
    public final void set( long i, long   l) { _vec.chunkForRow(i).set_abs(i, l); }
    public final void set( long i, double d) { _vec.chunkForRow(i).set_abs(i, d); }
    public final void set( long i, float  f) { _vec.chunkForRow(i).set_abs(i, f); }
    public final void setNA( long i        ) { _vec.chunkForRow(i).setNA_abs(i); }
    public final void set( long i, String str) { _vec.chunkForRow(i).set_abs(i, str); }
    public Futures close(Futures fs) { return _vec.postWrite(_vec.closeLocal(fs)); }
    public void close() { close(new Futures()).blockForPending(); }
  }

  /** Create a writer for bulk serial writes into this Vec.
   *  @return A Writer for bulk serial writes */
  public final Writer open() { return new Writer(this); }

  /** Close all chunks that are local (not just the ones that are homed)
   *  This should only be called from a Writer object */
  private Futures closeLocal(Futures fs) {
    int nc = nChunks();
    for( int i=0; i<nc; i++ )
      if( H2O.containsKey(chunkKey(i)) )
        chunkForChunkIdx(i).close(i, fs);
    return fs;                  // Flow-coding
  }

  /** Pretty print the Vec: {@code [#elems, min/mean/max]{chunks,...}}
   *  @return Brief string representation of a Vec */
  @Override public String toString() {
    RollupStats rs = RollupStats.getOrNull(this);
    String s = "["+length()+(rs == null ? ", {" : ","+rs._mins[0]+"/"+rs._mean+"/"+rs._maxs[0]+", "+PrettyPrint.bytes(rs._size)+", {");
    int nc = nChunks();
    for( int i=0; i<nc; i++ ) {
      s += chunkKey(i).home_node()+":"+chunk2StartElem(i)+":";
      // CNC: Bad plan to load remote data during a toString... messes up debug printing
      // Stupidly chunkForChunkIdx loads all data locally
      // s += chunkForChunkIdx(i).getClass().getSimpleName().replaceAll("Chunk","")+", ";
    }
    return s+"}]";
  }

  /** True if two Vecs are equal.  Checks for equal-Keys only (so it is fast)
   *  and not equal-contents.
   *  @return True if two Vecs are equal */
  @Override public boolean equals( Object o ) {
    return o instanceof Vec && ((Vec)o)._key.equals(_key);
  }
  /** Vec's hashcode, which is just the Vec Key hashcode.
   *  @return Vec's hashcode */
  @Override public int hashCode() { return _key.hashCode(); }

  /** Remove associated Keys when this guy removes.  For Vecs, remove all
   *  associated Chunks.
   *  @return Passed in Futures for flow-coding  */
  @Override public Futures remove_impl( Futures fs ) {
    for( int i=0; i<nChunks(); i++ )
      DKV.remove(chunkKey(i),fs);
    DKV.remove(rollupStatsKey(),fs);
    return fs;
  }

  // ======= Whole Vec Transformations ======

  /** Always makes a copy of the given vector which shares the same group as
   *  this Vec.  This can be expensive operation since it can force copy of
   *  data among nodes.
   *
   * @param vec vector which is intended to be copied
   * @return a copy of vec which shared the same {@link VectorGroup} with this vector
   */
  public Vec align(final Vec vec) {
    assert ! this.group().equals(vec.group()) : "Vector align expects a vector from different vector group";
    assert this.length() == vec.length() : "Trying to align vectors with different length!";
    Vec avec = makeZero(); // aligned vector
    new MRTask() {
      @Override public void map(Chunk c0) {
        long srow = c0._start;
        for (int r = 0; r < c0._len; r++) c0.set(r, vec.at(srow + r));
      }
    }.doAll(avec);
    avec._domain = _domain;
    return avec;
  }

  /** Transform this vector to enum.  If the vector is integer vector then its
   *  domain is collected and transformed to corresponding strings.  If the
   *  vector is enum an identity transformation vector is returned.
   *  Transformation is done by a {@link EnumWrappedVec} which provides a mapping
   *  between values - without copying the underlying data.
   *  @return A new Categorical Vec  */
  public EnumWrappedVec toEnum() {
    if( isEnum() ) return adaptTo(domain()); // Use existing domain directly
    if( !isInt() ) throw new IllegalArgumentException("Enum conversion only works on integer columns");
    // Right now, limited to small dense integers.
    if( min() < 0 || max() > 1000000 )
      throw new IllegalArgumentException("Enum conversion only works on small integers, but min="+min()+" and max = "+max());
    long[] domain= new CollectDomain().doAll(this).domain();
    if( domain.length > Categorical.MAX_ENUM_SIZE )
      throw new IllegalArgumentException("Column domain is too large to be represented as an enum: " + domain.length + " > " + Categorical.MAX_ENUM_SIZE);
    return adaptTo(ArrayUtils.toString(domain));
  }

  /** Make a Vec adapting this Enum vector to the 'to' Enum Vec.  The adapted
   *  EnumWrappedVec has 'this' as it's masterVec, but returns results in the 'to'
   *  domain (or just past it, if 'this' has elements not appearing in the 'to'
   *  domain). */
  public EnumWrappedVec adaptTo( String[] domain ) {
    return new EnumWrappedVec(group().addVec(),_espc,domain,this._key);
  }

  /** Transform this vector to strings.  If the
   *  vector is enum an identity transformation vector is returned.
   *  Transformation is done by a {@link StrWrappedVec} which provides a mapping
   *  between values - without copying the underlying data.
   *  @return A new String Vec  */
  public StrWrappedVec toStringVec() {
    if( !isEnum() ) throw new IllegalArgumentException("String conversion only works on enum columns");
    return new StrWrappedVec(group().addVec(),_espc,this._key);
  }

  /** This Vec does not have dependent hidden Vec it uses.
   *  @see EnumWrappedVec
   *  @return dependent hidden vector or <code>null</code>  */
//  public Vec masterVec() { return null; }

  /** Collect numeric domain of given vector
   *  A map-reduce task to collect up the unique values of an integer vector
   *  and returned as the domain for the vector.
   * */
  public static class CollectDomain extends MRTask<CollectDomain> {
    transient NonBlockingHashMapLong<String> _uniques;
    @Override protected void setupLocal() { _uniques = new NonBlockingHashMapLong<>(); }
    @Override public void map(Chunk ys) {
      for( int row=0; row< ys._len; row++ )
        if( !ys.isNA(row) )
          _uniques.put(ys.at8(row),"");
    }

    @Override public void reduce(CollectDomain mrt) {
      if( _uniques != mrt._uniques ) _uniques.putAll(mrt._uniques);
    }

    @Override public AutoBuffer write_impl( AutoBuffer ab ) {
      return ab.putA8(_uniques==null ? null : _uniques.keySetLong());
    }

    @Override public CollectDomain read_impl( AutoBuffer ab ) {
      assert _uniques == null || _uniques.size()==0;
      long ls[] = ab.getA8();
      _uniques = new NonBlockingHashMapLong<>();
      if( ls != null ) for( long l : ls ) _uniques.put(l,"");
      return this;
    }
    @Override public void copyOver(CollectDomain that) {
      _uniques = that._uniques;
    }

    /** Returns exact numeric domain of given vector computed by this task.
     * The domain is always sorted. Hence:
     *    domain()[0] - minimal domain value
     *    domain()[domain().length-1] - maximal domain value
     */
    public long[] domain() {
      long[] dom = _uniques.keySetLong();
      Arrays.sort(dom);
      return dom;
    }
  }

  /** Class representing the group of vectors.
   *
   *  Vectors from the same group have same distribution of chunks among nodes.
   *  Each vector is member of exactly one group.  Default group of one vector
   *  is created for each vector.  Group of each vector can be retrieved by
   *  calling group() method;
   *  
   *  The expected mode of operation is that user wants to add new vectors
   *  matching the source.  E.g. parse creates several vectors (one for each
   *  column) which are all colocated and are colocated with the original
   *  bytevector.
   *  
   *  To do this, user should first ask for the set of keys for the new vectors
   *  by calling addVecs method on the target group.
   *  
   *  Vectors in the group will have the same keys except for the prefix which
   *  specifies index of the vector inside the group.  The only information the
   *  group object carries is it's own key and the number of vectors it
   *  contains (deleted vectors still count).
   *  
   *  Because vectors (and chunks) share the same key-pattern with the group,
   *  default group with only one vector does not have to be actually created,
   *  it is implicit.
   *  
   *  @author tomasnykodym
   */
  public static class VectorGroup extends Iced {
    /** The common shared vector group for very short vectors */
    public static final VectorGroup VG_LEN1 = new VectorGroup();

    final int _len;
    final Key _key;
    private VectorGroup(Key key, int len){_key = key;_len = len;}

    public VectorGroup() {
      byte[] bits = new byte[26];
      bits[0] = Key.GRP;
      bits[1] = -1;
      UnsafeUtils.set4(bits, 2, -1);
      UnsafeUtils.set4(bits, 6, -1);
      UUID uu = UUID.randomUUID();
      UnsafeUtils.set8(bits,10,uu.getLeastSignificantBits());
      UnsafeUtils.set8(bits,18,uu. getMostSignificantBits());
      _key = Key.make(bits);
      _len = 0;
    }

    /** Returns Vec Key from Vec id# 
     *  @return Vec Key from Vec id# */
    public Key vecKey(int vecId) {
      byte [] bits = _key._kb.clone();
      bits[0] = Key.VEC;
      UnsafeUtils.set4(bits,2,vecId);//
      return Key.make(bits);
    }
    /** Task to atomically add vectors into existing group.
     *  @author tomasnykodym   */
    private static class AddVecs2GroupTsk extends TAtomic<VectorGroup>{
      final Key _key;
      int _n;          // INPUT: Keys to allocate; OUTPUT: start of run of keys
      private AddVecs2GroupTsk(Key key, int n){_key = key; _n = n;}
      @Override protected VectorGroup atomic(VectorGroup old) {
        int n = _n;             // how many
        // If the old group is missing, assume it is the default group-of-self
        // (having 1 ID already allocated for self), not a new group with
        // zero prior vectors.
        _n = old==null ? 1 : old._len; // start of allocated key run
        return new VectorGroup(_key, n+_n);
      }
    }

    /** Reserve a range of keys and return index of first new available key
     *  @return Vec id# of a range of Vec keys in this group */
    public int reserveKeys(final int n) {
      AddVecs2GroupTsk tsk = new AddVecs2GroupTsk(_key, n);
      tsk.invoke(_key);
      return tsk._n;
    }

    /** Gets the next n keys of this group.
     *  @param n number of keys to make
     *  @return arrays of unique keys belonging to this group.  */
    public Key[] addVecs(final int n) {
      int nn = reserveKeys(n);
      Key[] res = new Key[n];
      for( int i = 0; i < n; ++i )
        res[i] = vecKey(i + nn);
      return res;
    }
    /** Shortcut for {@code addVecs(1)}.
     *  @see #addVecs(int)
     *  @return a new Vec Key in this group   */
    public Key addVec() { return addVecs(1)[0]; }

    /** Pretty print the VectorGroup
     *  @return String representation of a VectorGroup */
    @Override public String toString() {
      return "VecGrp "+_key.toString()+", next free="+_len;
    }

    /** True if two VectorGroups are equal 
     *  @return True if two VectorGroups are equal */
    @Override public boolean equals( Object o ) {
      return o instanceof VectorGroup && ((VectorGroup)o)._key.equals(_key);
    }
    /** VectorGroups's hashcode
     *  @return VectorGroups's hashcode */
    @Override public int hashCode() {
      return _key.hashCode();
    }
  }

}
