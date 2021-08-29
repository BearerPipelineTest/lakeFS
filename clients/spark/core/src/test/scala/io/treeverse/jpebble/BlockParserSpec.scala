package io.treeverse.jpebble

import org.scalatest._
import matchers.should._
import funspec._
import com.dimafeng.testcontainers.{ForAllTestContainer, GenericContainer}

import java.io.File
import org.apache.commons.io.IOUtils
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy
import org.scalatest.matchers.must.Matchers.contain
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

import scala.io.Source

class BlockParserSpec extends AnyFunSpec with Matchers {
  val magicBytes = BlockParser.footerMagic


  describe("readMagic") {
    it("can read magic exactly") {
      val bytes = magicBytes.iterator
      BlockParser.readMagic(bytes)
      BlockParser.readEnd(bytes)
    }

    it("can read magic with a suffix") {
      val bytes = magicBytes :+ 123.toByte
      BlockParser.readMagic(magicBytes.iterator)
    }

    it("fails to read changed magic") {
      val bytes = magicBytes.toArray
      bytes(2) = (bytes(2) + 3).toByte
      assertThrows[BadFileFormatException]{
        BlockParser.readMagic(bytes.iterator)
      }
    }

    it("fails to read truncated magic") {
      val bytes = magicBytes.iterator.drop(3)
      assertThrows[BadFileFormatException]{
        BlockParser.readMagic(bytes)
      }
    }
  }

  describe("readUnsignedVarLong") {
    describe("handles sample case in doc") {
      Seq(
        ("parses 300", Seq(0xac, 0x02), 300)
      ).foreach({
        case ((name, b, value)) => {
          it(name) {
            val decodedValue = BlockParser.readUnsignedVarLong(b.map(_.toByte).iterator)
            decodedValue should be (value)
          }
        }
      })
    }

    describe("handles test cases from Golang encoding/binary test code") {
      // Generated from test code with https://play.golang.org/p/OWVVVkjmwQJ
      Seq(
        (Seq(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01), -9223372036854775808L),
        (Seq(0x81, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01), -9223372036854775807L),
        (Seq(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01), -1),
        (Seq(0x00), 0),
        (Seq(0x01), 1),
        (Seq(0x02), 2),
        (Seq(0x0a), 10),
        (Seq(0x14), 20),
        (Seq(0x3f), 63),
        (Seq(0x40), 64),
        (Seq(0x41), 65),
        (Seq(0x7f), 127),
        (Seq(0x80, 0x01), 128),
        (Seq(0x81, 0x01), 129),
        (Seq(0xff, 0x01), 255),
        (Seq(0x80, 0x02), 256),
        (Seq(0x81, 0x02), 257),
        (Seq(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x7f), 9223372036854775807L)
      ).foreach({
        case ((b, value)) => {
          it(s"parses ${value}") {
            val decodedValue = BlockParser.readUnsignedVarLong(b.map(_.toByte).iterator)
            decodedValue should be (value)
          }
        }
      })
    }
  }

  describe("readSignedVarLong") {
    describe("handles sample cases in docs") {
      Seq(
        ("parses zero", Seq(0x00), 0),
        ("parses -1", Seq(0x01), -1),
        ("parses 1", Seq(0x02), 1),
        ("parses -2", Seq(0x03), -2),
        ("parses 2", Seq(0x04), 2)
      ).foreach({
        case ((name, b, value)) => {
          it(name) {
            val decodedValue = BlockParser.readSignedVarLong(b.map(_.toByte).iterator)
            decodedValue should be (value)
          }
        }
      })
    }

    describe("handles test cases from Golang encoding/binary test code") {
      // Generated from test code with https://play.golang.org/p/O3bYzG8zUUX
      Seq(
        (Seq(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01), -9223372036854775808L),
        (Seq(0xfd, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01), -9223372036854775807L),
        (Seq(0x01), -1),
        (Seq(0x00), 0),
        (Seq(0x02), 1),
        (Seq(0x04), 2),
        (Seq(0x14), 10),
        (Seq(0x28), 20),
        (Seq(0x7e), 63),
        (Seq(0x80, 0x01), 64),
        (Seq(0x82, 0x01), 65),
        (Seq(0xfe, 0x01), 127),
        (Seq(0x80, 0x02), 128),
        (Seq(0x82, 0x02), 129),
        (Seq(0xfe, 0x03), 255),
        (Seq(0x80, 0x04), 256),
        (Seq(0x82, 0x04), 257),
        (Seq(0xfe, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01), 9223372036854775807L)
      ).foreach({
        case ((bytes, value)) => {
          it(s"parses ${value}") {
            val decodedValue = BlockParser.readSignedVarLong(bytes.map(_.toByte).iterator)
            decodedValue should be (value)
          }
        }
      })
    }
  }

  describe("readInt32") {
    describe("reads little-endian int32s") {
      Seq[(Seq[Int], Long)](
        (Seq(0xff, 0xff, 0xff, 0xff), -1),
        (Seq(0xff, 0xff, 0xff, 0x7f), 0x7fffffff),
        (Seq(0, 0, 0, 0x80), -0x80000000L),
        (Seq(0, 1, 0, 0), 256),
        (Seq(1, 2, 3, 4), 0x04030201),
        (Seq(1, 1, 0, 0), 257),
        (Seq(0x66, 0x77, 0x88, 0x99), 0x99887766L)
      ).foreach({
        case ((bytes, value)) => {
          it(s"parses ${value.toInt}") {
            val decodedValue = BlockParser.readInt32(bytes.map(_.toByte).iterator)
            decodedValue should be (value.toInt)
          }
        }
      })
    }
  }

  describe("read RocksDB SSTable") {
    // Load source data
    val hTxt = Source.fromInputStream(
      // Source.fromResource in Scala >= 2.12 but need to support 2.11.
      getClass.getClassLoader.getResourceAsStream("pebble-testdata/h.txt"))
    val histRe = " *(\\d+) *(\\w+) *$".r
    val expected = hTxt.getLines().map((line) =>
      line match {
        case histRe(count, word) => (word, count.toInt)
        case _ => throw new RuntimeException(s"Bad format h.txt line ${line}")
      }
    ).toMap

    it("internal: load h.txt") {
      expected should not be empty
    }

    val testFiles = Seq(
      "h.sst",
      "h.block-bloom.no-compression.sst",
      "h.no-compression.sst",
      "h.no-compression.two_level_index.sst",
      "h.table-bloom.no-compression.prefix_extractor.no_whole_key_filter.sst",
      "h.table-bloom.no-compression.sst",
      "h.table-bloom.sst"
    )

    /**
     * Lightweight fixture for running particular tests with an SSTable and
     * recovering its handle after running.  See
     * https://www.scalatest.org/scaladoc/1.8/org/scalatest/FlatSpec.html
     * ("Providing different fixtures to different tests") for how this works.
     */
    def withSSTable(sstFilename: String, test: BlockReadable => Any) = {
      // Copy SSTable to a readable file
      val sstContents =
        this.getClass.getClassLoader.getResourceAsStream("pebble-testdata/" + sstFilename)
      val tempFile = File.createTempFile("test-block-parser.", ".sst")
      tempFile.deleteOnExit()
      val out = new java.io.FileOutputStream(tempFile)
      IOUtils.copy(sstContents, out)
      sstContents.close()
      out.close()

      val in = new BlockReadableFileChannel(new java.io.FileInputStream(tempFile).getChannel)
      try {
        test(in)
      } finally {
        in.close()
      }
    }

    testFiles.foreach(
      (sstFilename) => {
        describe(sstFilename) {
          it("reads footer") {
            withSSTable(sstFilename, (in: BlockReadable) => {
              val bytes = in.iterate(in.length - BlockParser.footerLength, BlockParser.footerLength)
              val footer = BlockParser.readFooter(bytes)
              bytes.hasNext should be (false)
            })
          }

          it("dumps properties") {
            withSSTable(sstFilename, (in: BlockReadable) => {
              val bytes = in.iterate(in.length - BlockParser.footerLength, BlockParser.footerLength)
              val footer = BlockParser.readFooter(bytes)

              val props = BlockParser.readProperties(in, footer)

              Console.out.println(s"[DEBUG] Index type ${Binary.readable(props("rocksdb.block.based.table.index.type".getBytes))}")
            })
          }

          it("reads everything") {
            withSSTable(sstFilename, (in: BlockReadable) => {
              val it = BlockParser.entryIterator(in)
              val actual = it.map((entry) =>
                (new String(entry.key), new String(entry.value).toInt)).toMap

              actual should contain theSameElementsAs expected
            })
          }
        }
      })
  }
}

class CountedIteratorSpec extends AnyFunSpec with Matchers {
  val base = Seq[Byte](2, 3, 5, 7, 11)

  describe("CountedIterator") {
    it("returns original elements") {
      val actual = new CountedIterator(base.iterator).toSeq
      actual should contain theSameElementsInOrderAs base
    }

    it("counts elements") {
      val ci = new CountedIterator(base.iterator)
      ci.count should be (0)
      ci.next
      ci.count should be (1)
      ci.foreach((_) => Unit)
      ci.count should be (base.length)
    }
  }
}

class GolangContainerSpec extends AnyFunSpec with ForAllTestContainer {

  override val container: GenericContainer = GenericContainer("golang:1.16.2-alpine",
    classpathResourceMapping = Seq(
      ("parser-test/generate-sst.sh", "/local/generate-sst.sh", BindMode.READ_WRITE),
      ("parser-test/sst_files_generator.go", "/local/sst_files_generator.go", BindMode.READ_WRITE),
      ("parser-test/go.mod", "/local/go.mod", BindMode.READ_WRITE),
      ("parser-test/go.sum", "/local/go.sum", BindMode.READ_WRITE)),
    command = Seq("/local/generate-sst.sh"),
    waitStrategy = new LogMessageWaitStrategy().withRegEx("done\\n")
  );

  describe("A block parser") {
    it("should successfully parse 2-level index sstable") {
      val fileName = "two.level.idx"
      withTestFiles(fileName, (in: BlockReadable, expected: Seq[(String, String)]) => {
        val it = BlockParser.entryIterator(in)
        val actual = it.map((entry) =>
          (new String(entry.key), new String(entry.value))).toSeq.sortBy(_._1)

        val t4 = System.nanoTime

        actual should contain theSameElementsAs expected

        val duration4 = (System.nanoTime - t4) / 1e9d
        println("comparison time:")
        println(duration4)

      })
    }

    //TODO: make sure to print the values that are randomly generated from the scala tests
//    it("should successfully parse sstable of max size supported by lakeFS") {
//      ignore
//    }

  }


  def copyTestFile(fileName: String, suffix: String) : File =
    container.copyFileFromContainer("/local/" + fileName + suffix, in => {
      val tempOutFile = File.createTempFile("test-block-parser.", suffix)
      tempOutFile.deleteOnExit()
      val out = new java.io.FileOutputStream(tempOutFile)
      IOUtils.copy(in, out)
      in.close()
      out.close()
      return tempOutFile
    })


  /**
   * Lightweight fixture for running particular tests with an SSTable and
   * recovering its handle after running.  See
   * https://www.scalatest.org/scaladoc/1.8/org/scalatest/FlatSpec.html
   * ("Providing different fixtures to different tests") for how this works.
   */
  def withTestFiles(filename: String, test: (BlockReadable, Seq[(String, String)]) => Any) = {

    val t1 = System.nanoTime

    val tmpSstFile = copyTestFile(filename, ".sst")
    val tmpJsonFile = copyTestFile(filename, ".json")

    val duration1 = (System.nanoTime - t1) / 1e9d
    println("input copy:")
    println(duration1)

    val t2 = System.nanoTime

    val in = new BlockReadableFileChannel(new java.io.FileInputStream(tmpSstFile).getChannel)

    val duration2 = (System.nanoTime - t2) / 1e9d
    println("parsing time:")
    println(duration2)

    val t3 = System.nanoTime

    val jsonString = os.read(os.Path(tmpJsonFile.getAbsolutePath))
    val data = ujson.read(jsonString)

    val duration3 = (System.nanoTime - t3) / 1e9d
    println("json read time:")
    println(duration3)

    val expected = data.arr.map(e => (e("Key").str, e("Value").str)).toSeq.sortBy(_._1)

    try {
      test(in, expected)
    } finally {
      in.close()
    }
  }
}