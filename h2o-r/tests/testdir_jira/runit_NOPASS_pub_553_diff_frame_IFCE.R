setwd(normalizePath(dirname(R.utils::commandArgs(asValues=TRUE)$"f")))
source('../h2o-runit.R')

test.ifce<- function(conn) {

  hex <- as.h2o(conn, iris)
  zhex <- hex - hex
  # h2o.exec(zhex <- hex - hex)
  
  testEnd()
}

doTest("test ifce", test.ifce)
