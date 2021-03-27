package org.dbpedia.extraction.dump.util

import java.io.{File, FileInputStream, PrintWriter}

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.rdf.model.{Model, ModelFactory}

import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

object MinidumpDoc extends App {

  val shapesSHACLFile = new File(args(0))
  val miniExtractionBaseDir = new File(args(1))
  val urisFile = new File(args(2))
  val filePrintWriter = new PrintWriter(MinidumpDocConfig.shaclTestsTableFile)
  if (!(shapesSHACLFile.exists() && miniExtractionBaseDir.exists() && urisFile.exists())) {
    println(
      s"""Make sure
         |${shapesSHACLFile.getAbsolutePath}
         |${miniExtractionBaseDir.getAbsolutePath} (run: mvn test in dump module)
         |${urisFile.getAbsolutePath}
         |exists
         |""".stripMargin
    )
    System.exit(1)
  }

  sealed trait Target

  case class TargetNode(s: String) extends Target

  case class TargetSubjectOf(p: String) extends Target

  case class TargetObjectOf(p: String) extends Target

  case class TestDefinition(id: String, target: Target, additionalInformation: mutable.HashMap[String,String])

  // Get SHACL tests
  val prefixSHACL = "PREFIX sh: <http://www.w3.org/ns/shacl#> PREFIX prov: <http://www.w3.org/ns/prov#> PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> "

  val ontologySHACL = ModelFactory.createDefaultModel()
  ontologySHACL.read("http://www.w3.org/ns/shacl#")

  val shapesSHACL = ModelFactory.createDefaultModel()
  val filesToBeValidated = recursiveListFiles(new File("../extraction-framework/dump/src/test/resources/shacl-tests")).filter(_.isFile)
    .filter(_.toString.endsWith(".ttl"))
    .toList
  for (file <- filesToBeValidated) {
    shapesSHACL.read(file.getAbsolutePath)
  }


  val columnsNamesList: List[String] = List("wikipage-uri","shacl-test","issue","comment")
  val additionalInformationTypes: List[String] = List("issue","comment")

  val urisAndShaclTestsMap = new mutable.HashMap[String, ArrayBuffer[TestDefinition]]
  val testModel = ModelFactory.createRDFSModel(ontologySHACL, shapesSHACL)
  val exec = QueryExecutionFactory.create(
    prefixSHACL +
      """SELECT * {
        |  ?shape a sh:Shape .
        |  OPTIONAL { ?shape sh:targetNode ?targetNode . }
        |  OPTIONAL { ?shape sh:targetSubjectsOf ?subjectOf . }
        |  OPTIONAL { ?shape sh:targetObjectsOf ?objectOf . }
        |  OPTIONAL { ?shape prov:wasDerivedFrom ?issue . }
        |  OPTIONAL { ?shape rdfs:comment ?comment . }
        |}
        |""".stripMargin, testModel)

  val rs = exec.execSelect()

  val testsBuffer = new ListBuffer[TestDefinition]
  while (rs.hasNext) {
    val qs = rs.next()

    val targetNode = {
      if (qs.contains(MinidumpDocConfig.targetNode)) {
        println(s"tests ${Some(TargetNode(qs.get(MinidumpDocConfig.targetNode).asResource().getURI))} " +
          s"on target ${qs.get(MinidumpDocConfig.targetNode).asResource().getURI}")

        //None
        //TODO
        Some(TargetNode(qs.get(MinidumpDocConfig.targetNode).asResource().getURI))
      } else if (qs.contains(MinidumpDocConfig.subjectOf)) {
        println(s"tests ${Some(TargetSubjectOf(qs.get(MinidumpDocConfig.subjectOf).asResource().getURI))} " +
          s"on target ${qs.get(MinidumpDocConfig.subjectOf).asResource().getURI}")
       //None
        Some(TargetSubjectOf(qs.get(MinidumpDocConfig.subjectOf).asResource().getURI))
      } else if (qs.contains(MinidumpDocConfig.objectOf)) {
        Some(TargetObjectOf(qs.get(MinidumpDocConfig.objectOf).asResource().getURI))
      } else {
        None
      }
    }
    val listOfAdditionalInformation = new mutable.HashMap[String, String]()

    for(typeOfInformation <- additionalInformationTypes) {
      if (qs.contains(typeOfInformation)) {
        val information = qs.get(typeOfInformation).toString
        listOfAdditionalInformation.put(typeOfInformation, information)
      }
    }

    val shape = qs.get("shape").asResource()
    if (shape.isURIResource && targetNode.isDefined) {
      testsBuffer.append(TestDefinition(shape.getURI, targetNode.get,listOfAdditionalInformation))
    }
  }

  // Select From MiniExtraction
  if (testsBuffer.nonEmpty) {

    val minidumpURIs = convertWikiPageToDBpediaURI(urisFile)
    val miniExtraction = loadMiniExtraction(miniExtractionBaseDir)
    testsBuffer.foreach({
      testDef =>

        val queryString = new StringBuilder
        queryString.append("SELECT DISTINCT ?t { ")
        testDef.target match {
          case TargetNode(s) => queryString.append(s"VALUES ?t { <$s> } ?t ?p ?o . }")
          case TargetSubjectOf(p) => queryString.append(s"?t <$p> ?o . }")
          case TargetObjectOf(p) => queryString.append(s"?s <$p> ?t . }")
        }

        val exec = QueryExecutionFactory.create(queryString.toString(), miniExtraction)

        val rs = exec.execSelect()

        while (rs.hasNext) {
          val qs = rs.next
          val t = qs.get("t").asResource().getURI

          if (t.contains(MinidumpDocConfig.dbpediaUriPrefix) ) {

            val englishDbpediaUri = t.replace(MinidumpDocConfig.dbpediaUriPrefix,
              MinidumpDocConfig.englishDbpediaUriPrefix)

            if (minidumpURIs.contains(t) || minidumpURIs.contains(englishDbpediaUri)) {
              if (!minidumpURIs.contains(t) && minidumpURIs.contains(englishDbpediaUri)) {
                saveToMap(englishDbpediaUri, testDef)
              }
              else {
                println(s"tests ${testDef.target} on target $t")
                saveToMap(t, testDef)
              }
            }
          }
        }
    })

    writeShaclTestsTableToFile()

    def saveToMap(t: String, testDef: TestDefinition) = {
      val buffer = urisAndShaclTestsMap.get(t)
      buffer match {
        case Some(currentBuffer) => currentBuffer.append(testDef)
          urisAndShaclTestsMap.put(t, currentBuffer)
        case None => urisAndShaclTestsMap.put(t, ArrayBuffer(testDef))
      }
    }

    def writeColumnsNamesToFile(columnsNamesList: List[String]): Unit = {
      columnsNamesList match {
        case Nil =>
        case head::Nil => filePrintWriter.write(head+"\n")
        case head::(secondElement::tail) => {
          filePrintWriter.write(head+",")
          writeColumnsNamesToFile(secondElement::tail)
        }
      }
    }

    def writeShaclTestsTableToFile(): Unit = {

      writeColumnsNamesToFile(columnsNamesList)

      for (uriFromList <- minidumpURIs){

        if (urisAndShaclTestsMap.contains(uriFromList)) {
          val shaclTests = urisAndShaclTestsMap(uriFromList)
          for (test <- shaclTests) {
            println(test)
            val shaclTest = test.target match {
              case TargetNode(value) => value
              case TargetObjectOf(value) => value
              case TargetSubjectOf(value) => value
            }
            filePrintWriter.write(uriFromList + "," + shaclTest)

            val indexArray = new Array[String](columnsNamesList.length)
            for (typeOfInformation <- additionalInformationTypes) {
              if (test.additionalInformation.contains(typeOfInformation)) {
                val index = columnsNamesList.indexOf(typeOfInformation)
                indexArray(index) = test.additionalInformation(typeOfInformation)
              }
            }
            for (i <- 2 until columnsNamesList.length) {
              if (indexArray(i) == null ) {
                filePrintWriter.write(",")
              }
              else {
                filePrintWriter.write("," + indexArray(i).replaceAll(",",";"))
              }
            }
            filePrintWriter.write("\n")
          }
        }
        else {
          filePrintWriter.write(uriFromList + ",\n")
        }
      }
      filePrintWriter.close
    }
  }

  def convertWikiPageToDBpediaURI(urisF: File): Set[String] = {
    val source = scala.io.Source.fromFile(urisF)
    source.getLines().map({ wikiPage =>
      wikiPage.replace("https://", "http://")
        .replace("wikipedia.org/wiki/", "dbpedia.org/resource/")
        .replace("wikidata.org/wiki/", "wikidata.dbpedia.org/resource/")
    }).toSet
  }

  def loadMiniExtraction(d: File) = {
    val filesToBeValidated = recursiveListFiles(d).filter(_.isFile)
      .filter(_.toString.endsWith(".ttl.bz2"))
      .toList

    val model: Model = ModelFactory.createDefaultModel()
    for (file <- filesToBeValidated) {
      model.read(new BZip2CompressorInputStream(new FileInputStream(file)), null, "TTL")
    }
    model
  }

  def recursiveListFiles(d: File): Array[File] = {
    val these = d.listFiles
    these ++
      these.filter(_.isDirectory).flatMap(recursiveListFiles)
  }
}
