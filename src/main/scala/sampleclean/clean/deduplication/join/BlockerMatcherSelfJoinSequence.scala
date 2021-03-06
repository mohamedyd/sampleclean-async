package sampleclean.clean.deduplication.join

import sampleclean.api.SampleCleanContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import sampleclean.clean.deduplication.matcher.{DefaultHybridMatcher, Matcher}
import sampleclean.clean.deduplication.blocker.Blocker
import sampleclean.clean.featurize.Tokenizer._
import sampleclean.clean.featurize.Tokenizer

/**
 * This class acts as a wrapper for blocker + matcher routines.
 * This class has two constructors, requiring a blocker + List[matchers]
 * or a similarity join + List[Matchers]. We treat a similarity join
 * as a combination blocking and matching sequence.
 *
 * We call this the "BlockerMatcherSelfJoinSequence" because
 * in this class we apply the operation to the same sample.
 *
 * @param scc SampleClean Context
 * @param sampleTableName
 * @param blocker
 * @param matchers
 */
class BlockerMatcherSelfJoinSequence(scc: SampleCleanContext,
              		   sampleTableName:String,
              		   blocker: Blocker,
					   var matchers: List[Matcher]) extends Serializable {
	
	private [sampleclean] var join:SimilarityJoin = null

  /**
   * Create a BlockerMatcherSelfJoinSequence based on a similarity join
   * and a list of matchers.
   * @param scc SampleClean Context
   * @param sampleTableName
   * @param simjoin Similarity Join
   * @param matchers Because the Similarity Join should contain a matching
   *                 step, this parameter commonly refers to a matcher that
   *                 matches all pairs such as:
   *                 [[sampleclean.clean.deduplication.matcher.AllMatcher]]
   *                 or to an asynchronous matcher such as
   *                 [[sampleclean.clean.deduplication.matcher.ActiveLearningMatcher]]
   */
	def this(scc: SampleCleanContext,
              		   sampleTableName:String,
              		   simjoin: SimilarityJoin,
					   matchers: List[Matcher]) = {
		this(scc,sampleTableName,null:Blocker,matchers)
		join = simjoin
	}

  /**
   * Executes the algorithm.
   */
	def blockAndMatch(data:RDD[Row]):RDD[(Row,Row)] = {

		var blocks:RDD[Set[Row]] = null
		var matchedData:RDD[(Row,Row)] = null
		
		var start_time = System.nanoTime()

		if (blocker != null)
			blocks = blocker.block(data)
		else
			{ 
			  matchedData = join.join(data,data,false)
			  println("Entity Resolution Join Time: " + (System.nanoTime() - start_time)/ 1000000000)
			  
			  println("Candidate Pairs Size: " + matchedData.count)
			}	

		start_time = System.nanoTime()
		for (m <- matchers)
		{
			if (matchedData == null)
				matchedData = m.matchPairs(blocks)
			else
				matchedData = m.matchPairs(matchedData)
		}
		println("Entity Resolution Match Time: " + (System.nanoTime() - start_time)/ 1000000000)

		return matchedData
	}

  /**
   * Adds a new matcher to the matcher list
   * @param matcher
   */
	def addMatcher(matcher: Matcher) = {
		matchers = matcher :: matchers
		matchers = matchers.reverse  
	}

	def updateContext(newContext:List[String]) = {

		if(blocker != null)
			blocker.updateContext(newContext)

		if (join != null)
			join.updateContext(newContext)

		for (m <- matchers)
			m.updateContext(newContext)

		println("Context Updated to: " + newContext)
	}

  /**
   * Set a function that takes some action based on new results. This
   * needs to be done if there is an asynchronous matcher
   * at the end of the sequence.
   */
	def setOnReceiveNewMatches(func: RDD[(Row,Row)] => Unit) ={
		if(matchers.last.asynchronous)
			matchers.last.onReceiveNewMatches = func
		else
			println("[SampleClean] Asynchrony has no effect in this pipeline")
	}

	def printPipeline()={
			print("RDD[Row] --> ")
			if (blocker != null)
				print(blocker.getClass.getSimpleName + " --> ")
			else
				print("join(" + join.simfeature.getClass.getSimpleName + ") --> ")

			for(m <- matchers)
				print(m.getClass.getSimpleName + " --> ")

			println(" RDD[(Row,Row)]")
	}

	/**
	 * This function changes the similarity metric used for filtering in the Entity Resolution algorithm
	 * @type {[type]}
	 */
	def changeSimilarity(newSimilarity: String) = {
		if (newSimilarity == "EditDistance")
			throw new RuntimeException("You should use shortAttributeCanonicalize() instead")
		else if (join.isInstanceOf[PassJoin])
			throw new RuntimeException("You should use longAttributeCanonicalize() instead")

		join.setSimilarityFeaturizer(newSimilarity)
	}

	def changeTokenization(newTokenization: String) = {
		newTokenization match {
              case "WhiteSpace" => join.simfeature.tokenizer = new WhiteSpaceTokenizer()
              case "WhiteSpaceAndPunc" => join.simfeature.tokenizer = new WhiteSpacePunctuationTokenizer()
              case _ => throw new RuntimeException("Invalid Tokenizer: " + newTokenization)
      	}
	}


}

