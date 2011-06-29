/*
   Copyright 2011 Basis Technology Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package org.sleuthkit.hadoop;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.mahout.clustering.canopy.CanopyDriver;
import org.apache.mahout.clustering.kmeans.KMeansDriver;
import org.apache.mahout.common.distance.CosineDistanceMeasure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Contains routines for clustering hard drive document data which has been
 *  previously tokenized and vectorized.
 */
public class ClusterDocumentsJob {
    public static final Logger LOG = LoggerFactory.getLogger(ClusterDocumentsJob.class);

    public static void main(String[] argv) {
        if (argv.length < 5) {
            System.out.println("Usage: ClusterDocuments <input_dir> <output_dir> <dictionary_file> <t1> <t2> <image_hash> <friendly_name>");
            System.exit(1);
        }
        runPipeline(argv[0], argv[1], argv[2], .65, .65, argv[3], argv[4]);
    }

    public static int runPipeline(String input, String output, String dictionary, double t1, double t2, String imageID, String friendlyName) {
        Configuration conf = new Configuration();
        conf.set("mapred.child.java.opts", "-Xmx4096m");
        try {
            SKJobFactory.addDependencies(conf);
        } catch (IOException e) {
            LOG.error("Exception while adding dependencies.", e);
        }
        Path canopyInputPath = new Path(input);
        Path canopyOutputPath = new Path(output + "/canopy");

        Path kmeansInputPath = new Path(input);
        Path kmeansOutputPath = new Path(output + "/kmeans");
        // Canopy (AFAIK) only does one output pass, so the relevant
        // clusters should be found in this file. For KMeans, this may not
        // be the case. Note, though, that the final clusters with document
        // vectors will be in a different file.
        Path kmeansClusters = new Path(output + "/canopy/clusters-0");

        // perform canopy clustering on the grep-matched docs first,
        // to get some centroids for kmeans.
        
        
        try {
            CanopyDriver.run(conf, canopyInputPath, canopyOutputPath, new CosineDistanceMeasure(), t1, t2, true, false);
        } catch (Exception e) {
            LOG.error("Failure running mahout canopy.", e);
            return 1;
        }
        // now run kmeans using those centroids.
        try {
            KMeansDriver.run(conf, kmeansInputPath, kmeansClusters, kmeansOutputPath, new CosineDistanceMeasure(), .5, 20, true, false);
        } catch (Exception e) {
            LOG.error("Failure running mahout kmeans.", e);
            return 2;
        }

        try {
            ////////////////////////////////
            // Output top cluster matches //
            ////////////////////////////////
            Job job = SKJobFactory.createJob(imageID, friendlyName, JobNames.OUTPUT_CLUSTER_MATCH);
            job.setJarByClass(ClusterDocumentsJob.class);

            // Get the final kmeans iteration. This is sort of a pain but for
            // whatever reason hadoop has no mechanism to do this for us.
            FileSystem fs = FileSystem.get(job.getConfiguration());
            int i = 2;
            Path goodPath = new Path(output + "/kmeans/clusters-1");

            while (true) {
                Path testPath = new Path(output + "/kmeans/clusters-" + i);
                if (!fs.exists(testPath)) {
                    break;
                }
                i++;
                goodPath = testPath;
            }
            
            FileInputFormat.setInputPaths(job, goodPath);
            FileOutputFormat.setOutputPath(job, new Path(output + "/kmeans/topClusters/"));

            job.setMapperClass(TopFeatureMapper.class);
            job.setMapOutputKeyClass(NullWritable.class);
            job.setMapOutputValueClass(Text.class);
            // We need to reduce serially.
            job.setNumReduceTasks(1);

            job.setReducerClass(JSONArrayReducer.class);
            job.setOutputKeyClass(NullWritable.class);
            job.setOutputValueClass(Text.class);

            job.setInputFormatClass(SequenceFileInputFormat.class);
            job.setOutputFormatClass(TextOutputFormat.class);

            job.getConfiguration().set("org.sleuthkit.hadoop.dictionary", dictionary);
            
            job.waitForCompletion(true);
            
            ////////////////////////////////
            // Output Cluster->DocID JSON //
            ////////////////////////////////
            
            job = SKJobFactory.createJob(imageID, friendlyName, JobNames.OUTPUT_CLUSTER_JSON);
            job.setJarByClass(JSONClusterNameMapper.class);

            FileInputFormat.setInputPaths(job, new Path(output + "/kmeans/clusteredPoints/"));
            FileOutputFormat.setOutputPath(job, new Path(output + "/kmeans/jsonClusteredPoints/"));

            job.setMapperClass(JSONClusterNameMapper.class);
            job.setMapOutputKeyClass(NullWritable.class);
            job.setMapOutputValueClass(Text.class);
            // again, we need to reduce serially. We are crafting a single json object and so we must
            // have exactly one output file.
            job.setNumReduceTasks(1);
            job.setReducerClass(JSONArrayReducer.class);
            job.setOutputKeyClass(NullWritable.class);
            job.setOutputValueClass(Text.class);

            job.setInputFormatClass(SequenceFileInputFormat.class);
            job.setOutputFormatClass(TextOutputFormat.class);

            job.waitForCompletion(true);
            
            // Note that, since we limit the number of reduce tasks to 1, there should only be
            // one reduce 'part'.
            ClusterJSONBuilder.buildReport(
                    new Path(output + "/kmeans/topClusters/part-r-00000"), 
                    new Path(output + "/kmeans/jsonClusteredPoints/part-r-00000"),
                    new Path("/texaspete/data/" + imageID +  "/reports/data/documents.js"));
            return 0;
        } catch (IOException ex) {
            LOG.error("Failure while performing HDFS file IO.", ex);            
        } catch (ClassNotFoundException ex) {
            LOG.error("Error running job; class not found.", ex);            
        } catch (InterruptedException ex) {
            LOG.error("Hadoop job interrupted.", ex);
        }
        // we have failed; return non-zero error code.
        return 3;
        
    }
}