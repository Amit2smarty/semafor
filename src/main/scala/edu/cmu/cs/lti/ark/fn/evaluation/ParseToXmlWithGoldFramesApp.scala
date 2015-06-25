package edu.cmu.cs.lti.ark.fn.evaluation

import java.io.File

import com.google.common.base.Charsets.UTF_8
import com.google.common.io.Files
import com.google.common.io.Files._
import edu.cmu.cs.lti.ark.fn.Semafor
import edu.cmu.cs.lti.ark.fn.data.prep.formats.Sentence
import edu.cmu.cs.lti.ark.fn.data.prep.formats.SentenceCodec.{ConllCodec, SentenceIterator}
import edu.cmu.cs.lti.ark.fn.evaluation.PrepareFullAnnotationXML._
import edu.cmu.cs.lti.ark.util.ds.Range0Based
import resource._

import scala.collection.JavaConverters.{asScalaBufferConverter, asScalaIteratorConverter, seqAsJavaListConverter}
import scala.io.Source

object Constants {
  val TOKENIZED_FILE_TEMPLATE = "cv.%s.sentences.tokenized"
  val FRAME_ID_FILE_TEMPLATE = "cv.%s.sentences.frames"
  val DEP_PARSE_FILE_TEMPLATE = "cv.%s.sentences.turboparsed.basic.stanford.lemmatized.conll"
  val ALL_LEMMA_TAGS_FILE_TEMPLATE = "cv.%s.sentences.turboparsed.basic.stanford.all.lemma.tags"

  val K_BEST = 1
  val TARGET_FIELD = 5
  val SENTENCE_FIELD = 7
}

object SwabhaDiversityApp extends App {
  val Array(
    semaforHome,
    diversityType,
    tokenizedFilename,
    frameIdFilename,
    allLemmaTagsFilename,
    experimentsDir,
    depParseBaseFolder,
    outputDirName
  ) = args

  val tokenizedFile = new File(tokenizedFilename) // "training/data/naacl2012/cv.test.sentences.tokenized")
  val frameIdFile = new File(frameIdFilename) // "training/data/naacl2012/cv.test.sentences.frames")
  val allLemmaTagsFile = new File(allLemmaTagsFilename) //"training/data/naacl2012/cv.test.sentences.turboparsed.full.matsumoto.all.lemma.tags")
  val numSentences = Source.fromFile(tokenizedFile).getLines().length

  val EXPERIMENTS_DIR = new File(experimentsDir) // "experiments/turbo_matsumoto_20140723")
  val MODEL_DIR = new File(EXPERIMENTS_DIR, "model")

  parseAllSwabhasFiles(diversityType)

  def parseAllSwabhasFiles(diversityType: String) {
    val depParseFolder = new File(depParseBaseFolder, diversityType)

    val baseOutputFolder = new File(outputDirName, diversityType)
    val frameElementsOutputFolder = new File(baseOutputFolder, "frameElements")
    val xmlOutputFolder = new File(baseOutputFolder, "xml")

    // load up the model
//    System.err.println("\n\n%s".format(MODEL_DIR.getCanonicalPath))
    val sem = Semafor.getSemaforInstance(MODEL_DIR.getCanonicalPath)
    // parse each kthBest file
    for (depParseFile <- depParseFolder.listFiles) {
      val name = depParseFile.getName.split('.')(0)
      ParseToXmlWithGoldFrames.parseToXmlWithGoldFrames(
        name,
        tokenizedFile,
        allLemmaTagsFile,
        frameIdFile,
        depParseFile,
        frameElementsOutputFolder,
        xmlOutputFolder,
        sem
      )
    }
  }
}

object ParseToXmlWithGoldFramesApp extends App {
  import Constants._

  // CLI args:
  val semaforHome = args(0)
  val modelName = args(1)  // e.g. "adadelta_20150122" // "turbo_matsumoto_20140702"
  val infix = args(2)  // e.g. "dev" or "test"

  val dataDir = new File(semaforHome, "training/data/naacl2012")
  val experimentsDir = new File(new File(semaforHome, "experiments"), modelName)
  val modelDir = new File(experimentsDir, "model")
  val resultsDir = new File(experimentsDir, "output")

  // load up the model
  val sem = Semafor.getSemaforInstance(modelDir.getCanonicalPath)

  // parse
  ParseToXmlWithGoldFrames.parseToXmlWithGoldFrames(
    infix,
    new File(dataDir, TOKENIZED_FILE_TEMPLATE.format(infix)),
    new File(dataDir, ALL_LEMMA_TAGS_FILE_TEMPLATE.format(infix)),
    new File(dataDir, FRAME_ID_FILE_TEMPLATE.format(infix)),
    new File(dataDir, DEP_PARSE_FILE_TEMPLATE.format(infix)),
    resultsDir,
    resultsDir,
    sem
  )
}
  
object ParseToXmlWithGoldFrames {
  import Constants._

  def setSentenceId(line: String, sentenceId: String): String = {
    val fields = line.split("\t")
    fields(SENTENCE_FIELD) = sentenceId
    fields.mkString("\t")
  }

  def predictArgsForSentence(sentence: Sentence, frames: List[String], kBest: Int, sem: Semafor): List[String] = {
    // predictArgumentLines needs the sentenceId field to be 0, but we don't want to forget it
    val sentenceId = frames.head.split("\t")(SENTENCE_FIELD)
    //System.err.println(s"Sentence Id: $sentenceId")
    // set sentenceId to 0 and run arg id'ing
    val zeroed = frames.map(setSentenceId(_, "0"))
    val results: List[String] = sem.predictArgumentLines(sentence, zeroed.asJava, kBest).asScala.toList
    // set sentenceId back to the right value
    results.flatMap(_.split("\n")).map(setSentenceId(_, sentenceId))
  }

  def parseToXmlWithGoldFrames(infix: String,
                               tokenizedFile: File,
                               allLemmaTagsFile: File,
                               frameIdFile: File,
                               depParseFile: File,
                               feResultsDir: File,
                               xmlResultsDir: File,
                               sem: Semafor) {
    val outputFeFile = new File(feResultsDir, infix + ".argid.predict.frame.elements")

    System.err.println("\n\nParsing file: %s\n\n".format(depParseFile))

    parseToFeFormatWithGoldFrames(frameIdFile, depParseFile, outputFeFile, sem)

    val numSentences = Source.fromFile(tokenizedFile).getLines().length
    val outputXmlFile = new File(xmlResultsDir, infix + ".argid.predict.xml")

    System.err.println("\n\nGenerating xml: %s\n\n".format(outputXmlFile.getAbsolutePath))

    generateXMLForPrediction(
      outputFeFile.getAbsolutePath,
      new Range0Based(0, numSentences, false),
      allLemmaTagsFile.getAbsolutePath,
      tokenizedFile.getAbsolutePath,
      outputXmlFile.getAbsolutePath)
  }

  private def parseToFeFormatWithGoldFrames(frameIdFile: File,
                                            depParseFile: File,
                                            outputFile: File,
                                            sem: Semafor) {
    // read in dep parses
    val sentences = {
      val sentenceIterator = new SentenceIterator(ConllCodec, depParseFile)
      val unLemmatized = sentenceIterator.asScala.toArray
      sentenceIterator.close()
      unLemmatized.map(sem.addLemmas)
    }
    // read in gold frames and collate with sentences
    val sentencesAndFrames: List[(Sentence, List[String])] = {
      val frameLines = Files.readLines(frameIdFile, UTF_8).asScala.toList
      val framesBySentence = frameLines.groupBy(_.split("\t")(SENTENCE_FIELD).trim.toInt)
      framesBySentence.keys.toList.sorted.map(i => (sentences(i), framesBySentence(i)))
    }
    // run arg id'ing
    val resultLines = {
      val batchSize = 128
      val argResults = sentencesAndFrames.grouped(batchSize).flatMap(_.par.flatMap({
        case (sentence, frame) =>
          predictArgsForSentence(sentence, frame.toList, K_BEST, sem)
      }))
      argResults
    }
    // write results to file
    for (out <- managed(newWriter(outputFile, UTF_8));
         line <- resultLines) {
      out.write(line)
      out.write('\n')
    }
  }
}


// TODO: refactor

object ParseToXmlWithGoldTargetsApp extends App {
  import Constants._
  import ParseToXmlWithGoldFrames._

  // CLI args:
  val semaforHome = args(0)
  val modelName = args(1) // e.g. "adadelta_20150122" // "turbo_matsumoto_20140702"
  val infix = args(2) // e.g. "dev" or "test"

  val dataDir = new File(semaforHome, "training/data/naacl2012")
  val experimentsDir = new File(new File(semaforHome, "experiments"), modelName)
  val modelDir = new File(experimentsDir, "model")
  val resultsDir = new File(experimentsDir, "output")

  // load up the model
  val sem = Semafor.getSemaforInstance(modelDir.getCanonicalPath)

  // parse
  parseToXmlWithGoldTargets(infix, sem)

  def getTargetFromFrameLine(frameLine: String): List[Integer] = {
    frameLine.split("\t")(TARGET_FIELD).split("_").map(new Integer(_)).toList
  }

  def parseToXmlWithGoldTargets(infix: String, sem: Semafor) {
    val frameIdFile = new File(dataDir, FRAME_ID_FILE_TEMPLATE.format(infix))
    val depParseFile = new File(dataDir, DEP_PARSE_FILE_TEMPLATE.format(infix))
    val outputFeFile = new File(resultsDir, infix + ".full.predict.frame.elements")

    System.err.println("\n\nParsing file: %s\n\n".format(depParseFile))

    parseToFeFormatWithGoldTargets(frameIdFile, depParseFile, outputFeFile, sem)

    val tokenizedFile = new File(dataDir, TOKENIZED_FILE_TEMPLATE.format(infix))
    val numSentences = Source.fromFile(tokenizedFile).getLines().length
    val allLemmaTagsFile = new File(dataDir, ALL_LEMMA_TAGS_FILE_TEMPLATE.format(infix))
    val outputXmlFile = new File(resultsDir, infix + ".full.predict.xml")

    System.err.println("\n\nGenerating xml: %s\n\n".format(outputXmlFile.getAbsolutePath))

    generateXMLForPrediction(
      outputFeFile.getAbsolutePath,
      new Range0Based(0, numSentences, false),
      allLemmaTagsFile.getAbsolutePath,
      tokenizedFile.getAbsolutePath,
      outputXmlFile.getAbsolutePath)
  }

  private def parseToFeFormatWithGoldTargets(frameIdFile: File,
                                             depParseFile: File,
                                             outputFile: File,
                                             sem: Semafor) {
    // read in dep parses
    val sentences = {
      val sentenceIterator = new SentenceIterator(ConllCodec, depParseFile)
      val unLemmatized = sentenceIterator.asScala.toArray
      sentenceIterator.close()
      unLemmatized.map(sem.addLemmas)
    }
    // read in gold targets and collate with sentences
    val sentencesAndTargets: List[(Int, Sentence, List[List[Integer]])] = {
      val frameLines = Files.readLines(frameIdFile, UTF_8).asScala.toList
      val framesBySentence = frameLines.groupBy(_.split("\t")(SENTENCE_FIELD).trim.toInt)
      framesBySentence.keys.toList.sorted.map(i => (i, sentences(i), framesBySentence(i).map(getTargetFromFrameLine)))
    }
    // parse
    val resultLines: Iterator[String] = {
      val batchSize = 128
      sentencesAndTargets.grouped(batchSize).flatMap(_.par.flatMap {
        case (sentenceId, sentence, targets) =>
          System.err.println(s"Sentence Id: $sentenceId")
          // frame identification
          val idResult = sem.predictFrames(sentence, targets.map(_.asJava).asJava)
          // argument identification
          val idResultLines = sem.getArgumentIdInput(sentence, idResult)
          val results = sem.predictArgumentLines(sentence, idResultLines, 1).asScala
          results.flatMap(_.split("\n")).map(line => setSentenceId(line, sentenceId.toString))
      })
    }
    // write results to file
    for (
      out <- managed(newWriter(outputFile, UTF_8));
      line <- resultLines
    ) {
      out.write(line)
      out.write('\n')
    }
  }
}
