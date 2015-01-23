
package water;

import hex.ModelBuilder;
import hex.api.*;
import hex.deeplearning.DeepLearning;
import hex.example.Example;
import hex.glm.GLM;
import hex.grep.Grep;
import hex.kmeans.KMeans;
import hex.quantile.Quantile;
import hex.schemas.SplitFrameHandler;
import hex.splitframe.SplitFrame;
import hex.tree.gbm.GBM;
import hex.word2vec.Word2Vec;

import java.io.File;

public class H2OApp {
  public static void main2( String relpath ) { driver(new String[0],relpath); }

  public static void main( String[] args  ) { driver(args,System.getProperty("user.dir")); }

  private static void driver( String[] args, String relpath ) {

    // Fire up the H2O Cluster
    H2O.main(args);

    // Register REST API
    register(relpath);
  }

  static void register(String relpath) {

    H2O.registerResourceRoot(new File(relpath + File.separator + "h2o-web/src/main/resources/www"));
    H2O.registerResourceRoot(new File(relpath + File.separator + "h2o-core/src/main/resources/www"));

    // Register menu items and service handlers for algos
    H2O.registerGET("/3/Example",      hex.schemas.ExampleHandler.class,      "train",        "/Example","Example","Model",             "Train an Example model on the specified Frame.");
    H2O.registerGET("/3/DeepLearning", hex.schemas.DeepLearningHandler.class, "train",        "/DeepLearning", "Deep Learning","Model", "Train a Deep Learning model on the specified Frame.");
    H2O.registerGET("/3/GLM",          hex.schemas.GLMHandler.class,          "train",        "/GLM","GLM","Model",                     "Train a GLM model on the specified Frame.");
    H2O.registerGET("/3/KMeans",       hex.schemas.KMeansHandler.class,       "train",        "/KMeans","KMeans","Model",               "Train a KMeans model on the specified Frame.");
    H2O.registerGET("/3/GBM",          hex.schemas.GBMHandler.class,          "train",        "/GBM","GBM","Model",                     "Train a GBM model on the specified Frame.");
    H2O.registerGET("/3/Word2Vec",     hex.schemas.Word2VecHandler.class,     "train",        "/Word2Vec","Word2Vec","Model",           "Train a Word2Vec model on the specified Frame.");
    H2O.registerGET("/3/Synonyms",     hex.schemas.SynonymsHandler.class,     "findSynonyms", "/Synonyms", "Synonyms","Synonyms",       "Return the synonyms.");
    H2O.registerGET("/3/Grep",         hex.schemas.GrepHandler.class,         "train",        "/Grep","Grep","Model",                   "Run Grep on the specified Frame.");
    H2O.registerGET("/3/Quantile",     hex.schemas.QuantileHandler.class,     "train",        "/Quantile","Quantile","Model",           "Train a Quantile model on the specified Frame.");
    H2O.registerGET("/3/SplitFrame",   hex.schemas.SplitFrameHandler.class,   "train",        "/SplitFrame","SplitFrame","Model",       "Split a Frame into disjoint subsets.");

    /////////////////////////////////////////////////////////////////////////////////////////////
    // Register the algorithms and their builder handlers:
    ModelBuilder.registerModelBuilder("gbm", GBM.class);
    H2O.registerPOST("/3/ModelBuilders/gbm", GBMBuilderHandler.class, "train",                                                        "Train a GBM model on the specified Frame.");
    H2O.registerPOST("/3/ModelBuilders/gbm/parameters", GBMBuilderHandler.class, "validate_parameters",                               "Validate a set of GBM model builder parameters.");

    ModelBuilder.registerModelBuilder("kmeans", KMeans.class);
    H2O.registerPOST("/3/ModelBuilders/kmeans", KMeansBuilderHandler.class, "train",                                                  "Train a KMeans model on the specified Frame.");
    H2O.registerPOST("/3/ModelBuilders/kmeans/parameters", KMeansBuilderHandler.class, "validate_parameters",                         "Validate a set of KMeans model builder parameters.");

    ModelBuilder.registerModelBuilder("deeplearning", DeepLearning.class);
    H2O.registerPOST("/3/ModelBuilders/deeplearning", DeepLearningBuilderHandler.class, "train",                                      "Train a Deep Learning model on the specified Frame.");
    H2O.registerPOST("/3/ModelBuilders/deeplearning/parameters", DeepLearningBuilderHandler.class, "validate_parameters",             "Validate a set of Deep Learning model builder parameters.");

    ModelBuilder.registerModelBuilder("glm", GLM.class);
    H2O.registerPOST("/3/ModelBuilders/glm", GLMBuilderHandler.class, "train",                                                        "Train a GLM model on the specified Frame.");
    H2O.registerPOST("/3/ModelBuilders/glm/parameters", GLMBuilderHandler.class, "validate_parameters",                               "Validate a set of GLM model builder parameters.");

    ModelBuilder.registerModelBuilder("word2vec", Word2Vec.class);
    H2O.registerPOST("/3/ModelBuilders/word2vec", Word2VecBuilderHandler.class, "train",                                              "Train a Word2Vec model on the specified Frame.");
    H2O.registerPOST("/3/ModelBuilders/word2vec/parameters", Word2VecBuilderHandler.class, "validate_parameters",                     "Validate a set of Word2Vec model builder parameters.");

    ModelBuilder.registerModelBuilder("example", Example.class);
    H2O.registerPOST("/3/ModelBuilders/example", ExampleBuilderHandler.class, "train",                                                "Train an Example model on the specified Frame.");
    H2O.registerPOST("/3/ModelBuilders/example/parameters", ExampleBuilderHandler.class, "validate_parameters",                       "Validate a set of Example model builder parameters.");

    ModelBuilder.registerModelBuilder("quantile", Quantile.class);
    H2O.registerPOST("/3/ModelBuilders/quantile", QuantileBuilderHandler.class, "train","Train a Quantile model on the specified Frame.");
    H2O.registerPOST("/3/ModelBuilders/quantile/parameters", QuantileBuilderHandler.class, "validate_parameters",                     "Validate a set of Quantile model builder parameters.");

    ModelBuilder.registerModelBuilder("grep", Grep.class);
    H2O.registerPOST("/3/ModelBuilders/grep", GrepBuilderHandler.class, "train","Search a raw text file for matches");
    H2O.registerPOST("/3/ModelBuilders/grep/parameters", GrepBuilderHandler.class, "validate_parameters",                             "Validate a set of Grep parameters.");


    ModelBuilder.registerModelBuilder("splitframe", SplitFrame.class);
    H2O.registerPOST("/3/ModelBuilders/splitframe", SplitFrameHandler.class, "train", "split a frame into disjoint pieces.");
    H2O.registerPOST("/3/ModelBuilders/splitframe/parameters", SplitFrameBuilderHandler.class, "validate_parameters",                   "Validate a set of Split Frame parameters.");

    // Done adding menu items; fire up web server
    H2O.finalizeRegistration();
  }
}
