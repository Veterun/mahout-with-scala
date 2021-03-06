package clustering

import org.apache.mahout.math.RandomAccessSparseVector
import java.io.File
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.apache.mahout.math.Vector
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.SequenceFile
import org.apache.hadoop.io.LongWritable
import org.apache.mahout.math.VectorWritable
import org.apache.hadoop.io.Text
import org.apache.mahout.clustering.kmeans.Kluster
import org.apache.mahout.common.distance.EuclideanDistanceMeasure
import org.apache.mahout.common.HadoopUtil
import org.apache.hadoop.io.IntWritable
import org.apache.mahout.clustering.classify.WeightedVectorWritable
import org.apache.mahout.clustering.Cluster
import org.apache.mahout.common.distance.CosineDistanceMeasure
import org.apache.mahout.common.distance.ManhattanDistanceMeasure
import org.apache.mahout.common.distance.DistanceMeasure
import org.apache.mahout.common.distance.TanimotoDistanceMeasure
import org.apache.mahout.clustering.kmeans.KMeansDriver

object kmeans {

  import ClusterHelper.writePointsToFile

  val points = Array(
    (1, 1), (2, 1), (1, 2), (2, 2), (3, 3),
    (8, 8), (9, 8), (8, 9), (9, 9), (5, 6))

  def pointsToVectors(points: Array[Tuple2[Int, Int]]) = {
    points map { p =>
      val vec = new RandomAccessSparseVector(2)
      vec.setQuick(0, p._1)
      vec.setQuick(1, p._2)
      vec
    } toList
  }

  def runClustering(dmspec: Tuple3[String, DistanceMeasure, Int]) = {
    val (dmName, distanceMeasure, k) = dmspec
    val conf = new Configuration
    val fs = FileSystem.get(conf)

    val vectors = pointsToVectors(points)
    val testData = new File("testdata/points")
    HadoopUtil.delete(conf, new Path(testData.getPath()));

    if (!testData.exists()) testData.mkdirs()

    // write points to a sequence file
    writePointsToFile(vectors, conf, new Path("testdata/points/file1"))

    val clusterCentersPath = new Path("testdata/clusters/part-00000")
    val writer = new SequenceFile.Writer(fs, conf, clusterCentersPath, classOf[Text], classOf[Kluster])
    for (i <- (0 until k)) {
      val cluster = new Kluster(vectors(i),
        i, distanceMeasure)
      writer.append(new Text(cluster.getIdentifier()), cluster)
    }
    writer.close()

    // run kmeans on the points with k centers
    val outputLocation = "kmeans_output_" + dmName
    val kmeansOutput = new Path(outputLocation)
    HadoopUtil.delete(conf, kmeansOutput);
//
//    KMeansDriver.run(conf,
//        input,
//        clustersIn,
//        output,
//        convergenceDelta,
//        maxIterations,
//        runClustering,
//        clusterClassificationThreshold,
//        runSequential)
    
    KMeansDriver.run(
      conf,
      new Path("testdata/points"),
      new Path("testdata/clusters"),
      kmeansOutput,
      // distanceMeasure,
      0.001,
      10,
      true,
      0.0,
      false)

    // read output generated by kmeans
    val clustersPath = new Path(outputLocation + "/" + (Cluster.CLUSTERED_POINTS_DIR) + "/part-m-00000")
    val reader = new SequenceFile.Reader(fs, clustersPath, conf)

    val key = new IntWritable();
    val value = new WeightedVectorWritable()
    var clusters = List[(Vector, Int)]()
    while (reader.next(key, value)) {
      // println("%s belongs to cluster %s".format(value.toString(), key.toString()))
      clusters = (value.getVector(), key.get()) +: clusters
    }
    reader.close()
    clusters
  }

  def main(args: Array[String]) {

    val distanceMeasures = Map(
      "euclidean" -> new EuclideanDistanceMeasure,
      "cosine" -> new CosineDistanceMeasure,
      "manhattan" -> new ManhattanDistanceMeasure,
      "tanimoto" -> new TanimotoDistanceMeasure)

    val K = 3

    (distanceMeasures map (e => e._1 -> runClustering((e._1, e._2, K))))
      .foreach { x =>
        val (k, v) = x
        println(k)
        v map { y =>
          val vec = y._1
          val id = y._2
          val a = (0 until vec.size()) map (vec.get(_))
          (id, a)
        } groupBy (_._1) map { z =>
          z._1 -> (z._2 map (_._2))
        } foreach println
      }
  }
}
