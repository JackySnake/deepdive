package org.deepdive.inference

import anorm._
import au.com.bytecode.opencsv.CSVWriter
import java.io.{ByteArrayInputStream, File, FileOutputStream, FileWriter, StringWriter, Reader, FileReader, InputStream, InputStreamReader}
import org.deepdive.calibration._
import org.deepdive.datastore._
import org.deepdive.inference._
import org.deepdive.Logging
import org.deepdive.settings._
import org.deepdive.helpers.Helpers
import scala.collection.mutable.{ArrayBuffer, Set, SynchronizedBuffer}
import scala.io.Source
import java.io._

/* Stores the factor graph and inference results in a postges database. */
trait PostgresInferenceDataStoreComponent extends SQLInferenceDataStoreComponent {

  class PostgresInferenceDataStore(val dbSettings : DbSettings) extends SQLInferenceDataStore with Logging with PostgresDataStoreComponent {

    // def ds = PostgresDataStore

    // Default batch size, if not overwritten by user
    val BatchSize = Some(250000)
      

    /**
     * weightsFile: location to the binary format. Assume "weightsFile.text" file exists.
     */
    def bulkCopyWeights(weightsFile: String, dbSettings: DbSettings) : Unit = {
    
     val cmdfile = File.createTempFile(s"copy", ".sh")
     val writer = new PrintWriter(cmdfile)
     val copyStr = List("psql ", Helpers.getOptionString(dbSettings), " -c ", "\"", 
       """\COPY """, s"${WeightResultTable}(id, weight) FROM \'${weightsFile}\' DELIMITER ' ';", "\"").mkString("")
     log.info(copyStr)
     writer.println(copyStr)
     writer.close()
     Helpers.executeCmd(cmdfile.getAbsolutePath())
    }
    
    def bulkCopyVariables(variablesFile: String, dbSettings: DbSettings) : Unit = {
     
     val cmdfile = File.createTempFile(s"copy", ".sh")
     val writer = new PrintWriter(cmdfile)
     val copyStr = List("psql ", Helpers.getOptionString(dbSettings), " -c ", "\"", 
       """\COPY """, s"${VariableResultTable}(id, category, expectation) FROM \'${variablesFile}\' DELIMITER ' ';", "\"").mkString("")
     log.info(copyStr)
     writer.println(copyStr)
     writer.close()
     Helpers.executeCmd(cmdfile.getAbsolutePath())
   }

    /**
     * Drop and create a sequence, based on database type.
     */
    def createSequenceFunction(seqName: String): String =
      s"""DROP SEQUENCE IF EXISTS ${seqName} CASCADE;
          CREATE SEQUENCE ${seqName} MINVALUE -1 START 0;"""

    /**
     * Get the next value of a sequence
     */
    def nextVal(seqName: String): String =
      s""" nextval('${seqName}') """

    /**
     * Cast an expression to a type
     */
    def cast(expr: Any, toType: String): String =
      s"""${expr.toString()}::${toType}"""

    /**
     * Given a string column name, Get a quoted version dependent on DB.
     *          if psql, return "column"
     *          if mysql, return `column`
     */
    def quoteColumn(column: String): String =
      '"' + column + '"'
      
    /**
     * Generate random number in [0,1] in psql
     */
    def randomFunction: String = "RANDOM()"

    /**
     * Concatinate strings using "||" in psql/GP, adding user-specified
     * delimiter in between
     */
    def concat(list: Seq[String], delimiter: String): String = {
      delimiter match {
        case null => list.mkString(s" || ")
        case "" => list.mkString(s" || ")
        case _ => list.mkString(s" || '${delimiter}' || ")
      }
    }
    
    def analyzeTable(table: String) = s"ANALYZE ${table}"

    /**
     * For postgres, do not create indexes for query table
     */
    override def createIndexesForQueryTable(queryTable: String, weightVariables: Seq[String]) = {
      // do nothing
    }

    // assign senquential ids to table's id column
    def assignIds(table: String, startId: Long, sequence: String) : Long = {
      if (ds.isUsingGreenplum()) {
        ds.executeSqlQueries(s"SELECT fast_seqassign('${table.toLowerCase()}', ${startId});");
      } else {
        ds.executeSqlQueries(s"UPDATE ${table} SET id = ${nextVal(sequence)};")
      }
      var count : Long = 0
      ds.executeSqlQueryWithCallback(s"""SELECT COUNT(*) FROM ${table};""") { rs =>
        count = rs.getLong(1)
      }
      return count
    }
    
    // create fast sequence assign function for greenplum
    def createAssignIdFunctionGreenplum() : Unit = {
      if (!ds.isUsingGreenplum()) return
      ds.executeSqlQuery(SQLFunctions.fastSequenceAssignForGreenplum)
    }

  }
}
