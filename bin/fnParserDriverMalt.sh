#!/bin/bash
#    The driver script for running SEMAFOR using MaltParser as its dependency parser.
#    Written by Sam Thomson (sthomson@cs.cmu.edu)
#    Based on code by Dipanjan Das (dipanjan@cs.cmu.edu)
#    Copyright (C) 2011
#    Sam Thomson
#    Language Technologies Institute, Carnegie Mellon University
#
#    This program is free software: you can redistribute it and/or modify
#    it under the terms of the GNU General Public License as published by
#    the Free Software Foundation, either version 3 of the License, or
#    (at your option) any later version.
#
#    This program is distributed in the hope that it will be useful,
#    but WITHOUT ANY WARRANTY; without even the implied warranty of
#    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#    GNU General Public License for more details.
#
#    You should have received a copy of the GNU General Public License
#    along with this program.  If not, see <http://www.gnu.org/licenses/>.

set -e # fail fast


MY_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
source "${MY_DIR}/config.sh"

if [ $# -lt 2 -o $# -gt 3 ]; then
   echo "USAGE: `basename "${0}"` <input-file> <output-file> [<output-format>]"
   exit 1
fi

if [ `uname -m` != "x86_64" ]; then
   echo -n "\nNOTE: You should really be running this on a 64-bit architecture."
   # give the user the chance to CTRL-C here...
   for dot in 1 2 3 4 5 6; do
       sleep 1
       echo -n "."
   done
   echo
fi

# location of input file. must be absolute path
INPUT_FILE="${1}"

# where to write the output
OUTPUT_FILE="${2}"

# what format to use to write the output
if [ ${3} = "xml" ]
then
    OUTPUT_FORMAT="xml"
elif [ ${3} = "json" ] || [ -z ${3} ]
then
    OUTPUT_FORMAT="json" # default
else
    echo "output format must be \"xml\" or \"json\"."
    exit 1
fi



TEMP_DIR=$(mktemp -d -t semafor.XXXXXXXXXX)
echo "TEMP_DIR: ${TEMP_DIR}"

TOKENIZED="${TEMP_DIR}/tokenized"
POS_TAGGED="${TEMP_DIR}/pos.tagged"
TEST_PARSED_FILE="${TEMP_DIR}/conll"
ALL_LEMMA_TAGS_FILE="${TEMP_DIR}/all.lemma.tags"
FRAME_ELEMENTS_OUTPUT_FILE="${TEMP_DIR}/fes"

CLASSPATH=".:${SEMAFOR_HOME}/target/Semafor-3.0-alpha-01.jar"
echo CLASSPATH="$CLASSPATH"


echo "**********************************************************************"
echo "Tokenizing file: ${INPUT_FILE}"
sed -f ${SEMAFOR_HOME}/scripts/tokenizer.sed ${INPUT_FILE} > ${TOKENIZED}
echo "Finished tokenization."
echo "**********************************************************************"
echo
echo

echo "**********************************************************************"
echo "Part-of-speech tagging tokenized data...."
pushd ${SEMAFOR_HOME}/scripts/jmx
./mxpost tagger.project < ${TOKENIZED} > ${POS_TAGGED}
popd
# convert to conll so Malt can read it
${JAVA_HOME_BIN}/java -classpath ${CLASSPATH} \
    edu.cmu.cs.lti.ark.fn.data.prep.formats.ConvertFormat \
    --input ${POS_TAGGED} \
    --inputFormat pos \
    --output ${POS_TAGGED}.conll \
    --outputFormat conll
echo "Finished part-of-speech tagging."
echo "**********************************************************************"
echo
echo

echo "**********************************************************************"
echo "Running MaltParser...."
pushd ${SEMAFOR_HOME}/scripts/maltparser-1.7.2
java -Xmx2g -jar maltparser-1.7.2.jar -w ${MODEL_DIR} -c engmalt.linear-1.7 -i ${POS_TAGGED}.conll -o ${TEST_PARSED_FILE}
echo "Finished converting malt parse to conll format."
echo "**********************************************************************"
echo
echo


if [ "${AUTO_TARGET_ID_MODE}" == "relaxed" ]
then 
    RELAXED_FLAG=yes
else
    RELAXED_FLAG=no
fi


if [ "${USE_GRAPH_FILE}" == "yes" ]
then
    GRAPH_FILE="${MODEL_DIR}/sparsegraph.gz"
else
    GRAPH_FILE=null
fi

echo "**********************************************************************"
echo "Performing frame-semantic parsing"
cd ${SEMAFOR_HOME}
time ${JAVA_HOME_BIN}/java \
    -classpath ${CLASSPATH} \
    -Xms4g -Xmx4g \
    edu.cmu.cs.lti.ark.fn.parsing.ParserDriver \
    mstmode:noserver \
    mstserver:null \
    mstport:12345 \
    posfile:${POS_TAGGED} \
    test-parsefile:${TEST_PARSED_FILE} \
    fnidreqdatafile:${MODEL_DIR}/reqData.jobj \
    goldsegfile:${GOLD_TARGET_FILE} \
    userelaxed:${RELAXED_FLAG} \
    testtokenizedfile:${TOKENIZED} \
    idmodelfile:${MODEL_DIR}/idmodel.dat \
    alphabetfile:${MODEL_DIR}/parser.conf \
    framenet-femapfile:${MODEL_DIR}/framenet.frame.element.map \
    eventsfile:${TEMP_DIR}/events.bin \
    spansfile:${TEMP_DIR}/spans \
    model:${MODEL_DIR}/argmodel.dat \
    useGraph:${GRAPH_FILE} \
    frameelementsoutputfile:${FRAME_ELEMENTS_OUTPUT_FILE} \
    alllemmatagsfile:${ALL_LEMMA_TAGS_FILE} \
    requiresmap:${MODEL_DIR}/requires.map \
    excludesmap:${MODEL_DIR}/excludes.map \
    decoding:${DECODING_TYPE} \
    k-best-output:1

end=`wc -l ${TOKENIZED}`
end=`expr ${end% *}`
echo "${end} sentences"


echo "Producing final ${OUTPUT_FORMAT} document:"
if [ "${OUTPUT_FORMAT}" == "xml" ] ; then
    time ${JAVA_HOME_BIN}/java -classpath ${CLASSPATH} \
        -Xms4g -Xmx4g \
        edu.cmu.cs.lti.ark.fn.evaluation.PrepareFullAnnotationXML \
        testFEPredictionsFile:${FRAME_ELEMENTS_OUTPUT_FILE} \
        startIndex:0 \
        endIndex:${end} \
        testParseFile:${ALL_LEMMA_TAGS_FILE} \
        testTokenizedFile:${TOKENIZED} \
        outputFile:${OUTPUT_FILE} ;
elif [ "${OUTPUT_FORMAT}" == "json" ] ; then
    time ${JAVA_HOME_BIN}/java -classpath ${CLASSPATH} \
        -Xms8g -Xmx8g \
        edu.cmu.cs.lti.ark.fn.evaluation.PrepareFullAnnotationJson \
        testFEPredictionsFile:${FRAME_ELEMENTS_OUTPUT_FILE} \
        testTokenizedFile:${TOKENIZED} \
        outputFile:${OUTPUT_FILE} ;
else
    exit 1 ;
fi


echo "Finished frame-semantic parsing."
echo "**********************************************************************"
echo
echo

rm -r "${TEMP_DIR}"
