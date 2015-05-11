#!/bin/bash -e                                                                                       
source "$(dirname ${BASH_SOURCE[0]})/../bin/config.sh"

# choose a name for the model to train
model_name="basic_tbps" # make this directory in ../experiments/
mdl="basic" # prefix for the all.lemma.tags file

# RUN swabha_all_lemma_tags.sh AND CHANGE parsed_file
# AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
#
#
# ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ
# should set to roughly the number of cores available



num_threads=10
gc_threads=6

classpath="${CLASSPATH}"
# the directory that contains framenet.frame.element.map and framenet.original.map
datadir="${SEMAFOR_HOME}/training/data"

experiments_dir="${SEMAFOR_HOME}/experiments/${model_name}"

# the directory the resulting model will end up in
model_dir="${experiments_dir}/model"

# the directory the evaluation results will end up in
results_dir="${experiments_dir}/results"

id_features="ancestor"

old_model_dir="${MALT_MODEL_DIR}"

fn_id_req_data_file="${model_dir}/reqData.jobj"


# paths to the gold-standard annotated sentences, and dependency-parsed version of it
training_dir="${datadir}/naacl2012"
fe_file="${training_dir}/cv.train.sentences.frame.elements"
fe_file_length=`wc -l ${fe_file}`
fe_file_length=`expr ${fe_file_length% *}`
parsed_file="${training_dir}/cv.train.sentences.turboparsed.${mdl}.stanford.all.lemma.tags"

# path to store the alphabet we create:
alphabet_file="${model_dir}/alphabet.dat"

SCAN_DIR="${model_dir}/scan"

echo num_threads="${num_threads}"
echo gc_threads="${gc_threads}"
echo datadir="${datadir}"
echo id_features="${id_features}"
echo fn_id_req_data_file="${fn_id_req_data_file}"
echo training_dir="${training_dir}"
echo fe_file="${fe_file}"
echo parsed_file="${parsed_file}"
echo fe_file_length="${fe_file_length}"
echo alphabet_file="${alphabet_file}"
echo SCAN_DIR="${SCAN_DIR}"
