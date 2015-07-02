#!/bin/bash
set -e # fail fast
source "$(dirname ${BASH_SOURCE[0]})/../../training/config.sh"

div_metric="$1"


echo "Root of Project:"
echo ${SEMAFOR_HOME}
echo

fn_1_5_dir="${datadir}/framenet15/"
frames_single_file="${fn_1_5_dir}/framesSingleFile.xml"
relation_modified_file="${fn_1_5_dir}/frRelationModified.xml"
gold_xml="${training_dir}/cv.test.sentences.lrb.xml"
exp_dir="${SEMAFOR_HOME}/experiments/${model_name}"
inp_xml_dir="${exp_dir}/output/${div_metric}/xml"

echo "Performing arg id for test data"

mkdir -p ${inp_xml_dir}
mkdir -p ${exp_dir}/output/${div_metric}/frameElements

/usr0/home/sswayamd/scala-2.10.4/bin/scala \
 -cp ".:target/Semafor-3.0-alpha-05-SNAPSHOT.jar" \
 -J-Xms4g \
 -J-Xmx4g \
 -J-XX:ParallelGCThreads=2 \
 scripts/scoring/SwabhaDiversity.scala \
 $model_name \
 $div_metric

echo "Performing exact evaluation"

res_dir="${exp_dir}/results/${div_metric}"
mkdir -p "${res_dir}"

kthbest_xml_dir=$(cd "${inp_xml_dir}" > /dev/null && echo *)
cd "${SEMAFOR_HOME}"

exact_dir="${res_dir}/exact_count_gold_frames"
mkdir -p "${exact_dir}"
for kthbest_xml in ${kthbest_xml_dir}; do
    echo "Argument Labeling Exactt Results: ${inp_xml_dir}/${kthbest_xml}"
    ./scripts/scoring/fnSemScore_swabha.pl \
        -l \
        -n \
        -e \
        -s \
        "${frames_single_file}" \
        "${relation_modified_file}" \
        "${gold_xml}" \
        "${inp_xml_dir}/${kthbest_xml}" > "${exact_dir}/${kthbest_xml}"
done

echo "Performing exact evaluation - not counting gold frames"

honest_dir="${res_dir}/exact"
mkdir -p "${honest_dir}"
for kthbest_xml in ${kthbest_xml_dir}; do
    echo "Argument Labeling Results: Not counting gold frames ${inp_xml_dir}/${kthbest_xml}"
    ./scripts/scoring/fnSemScore_swabha.pl \
        -l \
        -n \
        -e \
        -a \
        -s \
        "${frames_single_file}" \
        "${relation_modified_file}" \
        "${gold_xml}" \
        "${inp_xml_dir}/${kthbest_xml}" > "${honest_dir}/${kthbest_xml}"
done
