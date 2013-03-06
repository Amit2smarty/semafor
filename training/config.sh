#!/bin/bash -e                                                                                       

source "$(dirname ${0})/../bin/config"

# choose a name for the model to train
model_name="models_0.0"

# should set to roughly the number of cores available
num_threads=8


classpath="..:${SEMAFOR_HOME}:${SEMAFOR_HOME}/lib/semafor-deps.jar"

# the directory that contains framenet.frame.element.map and framenet.original.map
datadir="${SEMAFOR_HOME}/training/data"
# the directory that contains all the lexical unit xmls for FrameNet 1.5
# you can also add your own xmls to this directory
# for format information, take a look at the lu/ directory under the FrameNet release
luxmldir="${datadir}/lu"

# the directory the resulting model will end up in
model_dir="${datadir}/${model_name}"


# config files
wordnet_config_file="${SEMAFOR_HOME}/dict/file_properties.xml"
stopwords_file="${SEMAFOR_HOME}/dict/stopwords.txt"

framenet_map_file="${datadir}/framenet.original.map"
all_related_words_file="${datadir}/allrelatedwords.ser"
hv_correspondence_file="${datadir}/hvmap.ser"
wn_related_words_for_words_file="${datadir}/wnallrelwords.ser"
wn_map_file="${datadir}/wnMap.ser"
revised_map_file="${datadir}/revisedrelmap.ser"
lemma_cache_file="${datadir}/hvlemmas.ser"
fn_id_req_data_file="${datadir}/reqData.jobj"


# paths to the gold-standard annotated sentences, and dependency-parsed version of it
training_dir="${datadir}/naacl2012"
fe_file="${training_dir}/cv.train.sentences.frame.elements"
parsed_file="${training_dir}/cv.train.sentences.all.lemma.tags"
fe_file_length=`wc -l ${fe_file}`
fe_file_length=`expr ${fe_file_length% *}`

# path to store the alphabet we create:
alphabet_file=${datadir}/alphabet_combined.dat
