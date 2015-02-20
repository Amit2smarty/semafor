#!/bin/bash

set -e # fail fast

# step 4iii: Training.

source "$(dirname ${0})/config.sh"

${JAVA_HOME_BIN}/java -classpath ${classpath} -Xms8000m -Xmx8000m \
  edu.cmu.cs.lti.ark.fn.parsing.Training \
  model:${experiments_dir}/argmodel.dat \
  alphabetfile:${SCAN_DIR}/parser.conf.unlabeled \
  localfeaturescache:${SCAN_DIR}/featurecache.jobj \
  lambda:0.1 \
  numthreads:${num_threads} \
