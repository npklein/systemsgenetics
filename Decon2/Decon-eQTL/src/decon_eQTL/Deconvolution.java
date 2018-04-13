package decon_eQTL;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.commons.math3.stat.correlation.SpearmansCorrelation;
import JSci.maths.statistics.FDistribution;
import decon_eQTL.CellCount;
import decon_eQTL.Qtl;

public class Deconvolution {
	private int QTLsFiltered = 0;
	private String outputFolder;
	public CellCount cellCounts;
	public ExpressionData expressionData;
	public HashMap<String,ArrayList<String>> geneSnpPairs;
	private CommandLineOptions commandLineOptions;
	public GenotypeData genotypeData;

	public Deconvolution(CommandLineOptions commandLineOptions) {
		this.commandLineOptions = commandLineOptions;
	}

	/*
	 * Read all the input data
	 * 
	 * @throw IllegalAccessException	If file can't be accessed
	 * @throw IOException	If file can not be read
	 */
	public void readInputData() throws IllegalAccessException, IOException {
		outputFolder = commandLineOptions.getOutfolder();
		cellCounts = new CellCount();
		cellCounts.readCellCountData(commandLineOptions.getCellcountFile());
		
		geneSnpPairs = Utils.parseSnpPerGeneFile(commandLineOptions.getSnpsToTestFile());
		String expressionFile = commandLineOptions.getExpressionFile();
		DeconvolutionLogger.log.info(String.format("Parse expression data from %s",expressionFile));
		expressionData = new ExpressionData(expressionFile);
		DeconvolutionLogger.log.info("Done");
		String genotypeFile = commandLineOptions.getGenotypeFile();
		DeconvolutionLogger.log.info(String.format("Parse genotype data from %s",genotypeFile));
		genotypeData = new GenotypeData(genotypeFile);

		DeconvolutionLogger.log.info("Done");
		if (!expressionData.getSampleNames().equals(genotypeData.getSampleNames())){
			ArrayList<String> differences = Utils.getDifferencesBetweenLists(expressionData.getSampleNames(), genotypeData.getSampleNames());
			throw new RuntimeException(String.format("Samplenames not the same in expression and genotype file, or not in the same order."+
					"\nexpression samples not in genotypes (%d): %s\ngenotype samples not in expression (%d): %s\n",
					differences.get(0).length(), differences.get(0), 
					differences.get(1).length(), differences.get(1)));
		}
	}

	/**
	 * For each of the gene-SNP pair in the SnpsToTestFile run deconvolution
	 * 
	 * @param commandLineOptions	commandLineOptions to run it with 
	 * 
	 * @return Deconvolution results
	 * 
	 * @throws RuntimeException
	 * @throws IllegalAccessException 
	 * @throws IOException 
	 */
	public List<DeconvolutionResult> runDeconPerGeneSnpPair() throws RuntimeException, IllegalAccessException, IOException{

		//file to write all samples in that got filtered out
		Path filteredQTLsFile = Paths.get(outputFolder+"/filteredQTLs.csv");
		List<String> filteredQTLsOutput = new ArrayList<String>();
		filteredQTLsOutput.add("QTL\treason");

		int whileIndex = 0;
		long time = System.currentTimeMillis();
		List<DeconvolutionResult> deconvolutionResults = new ArrayList<DeconvolutionResult>();
		int QTLsTotal = 0;
		HashMap<String, double[]> geneExpressionLevels = expressionData.getGeneExpression();
		int skippedGenotypeGeneCombinations = 0;
		for(String gene : geneSnpPairs.keySet()){
			for(String genotype : geneSnpPairs.get(gene)){
				if(commandLineOptions.getTestRun() && whileIndex == 100){
					break;
				}
				if (whileIndex % 500 == 0) {
					long completedIn = System.currentTimeMillis() - time;
					DeconvolutionLogger.log.info(String.format("Processed %d gene-SNP pairs - %s - skipped %d gene-SNP combinations", whileIndex, DurationFormatUtils.formatDuration(completedIn, "HH:mm:ss:SS"), skippedGenotypeGeneCombinations));
				}
				++whileIndex;
				String qtlName = gene+'_'+genotype;
				++QTLsTotal;
				double[] dosages = genotypeData.getGenotypes().get(genotype);
				if(dosages == null){
					DeconvolutionLogger.log.info(String.format("Error: Genotype %s included in gene/snp combinations to test, but not available in the expression file!",genotype));
					throw new RuntimeException(String.format("Error: Genotype %s included in gene/snp combinations to test, but not available in the expression file!",genotype));
				}
				double[] expressionLevels = geneExpressionLevels.get(gene);

				if(expressionLevels == null){
					DeconvolutionLogger.log.info(String.format("Error: Gene %s included in gene/snp combinations to test, but not available in the expression file!",gene));
					throw new RuntimeException(String.format("Gene %s included in gene/snp combinations to test, but not available in the expression file!",gene));

				}
				DeconvolutionResult deconResult = deconvolution(expressionLevels, dosages, qtlName);
				deconvolutionResults.add(deconResult);
			}
		}
		DeconvolutionLogger.log.info(String.format("Skipped %d gene-SNP combinations (because genotype in SNP-pair file but not in genotype file)",skippedGenotypeGeneCombinations));
		DeconvolutionLogger.log.info(String.format("QTLs passed: %d", QTLsTotal-(QTLsFiltered+skippedGenotypeGeneCombinations)));
		DeconvolutionLogger.log.info(String.format("QTLs filtered: %d", QTLsFiltered));
		DeconvolutionLogger.log.info(String.format("Total: %d",QTLsTotal-skippedGenotypeGeneCombinations));
		Files.write(filteredQTLsFile, filteredQTLsOutput, Charset.forName("UTF-8"));
		return deconvolutionResults;
	}

	/**
	 * Write the deconvolution results
	 * 
	 * @param deconvolutionResult The deconvolution result
	 */
	public void writeDeconvolutionResults(List<DeconvolutionResult> deconvolutionResults) throws IllegalAccessException, IOException{
		List<String> celltypes = cellCounts.getAllCelltypes();
		String header = "\t"+Utils.listToTabSeparatedString(celltypes, "_pvalue");

		DeconvolutionLogger.log.info("Getting decon result with full model info for writing the header");
		// celltypes.size()*2 because there are twice as many betas as celltypes (CC% & CC%:GT)
		InteractionModelCollection firstInteractionModelCollection = deconvolutionResults.get(0).getInteractionModelCollection();
		InteractionModel bestFullModelForHeaderOnly = firstInteractionModelCollection.getBestFullModel();

		for(int i = 1; i < cellCounts.getNumberOfCelltypes()*2 + 1; ++i){
			header += "\tBeta" + Integer.toString(i) +"_"+bestFullModelForHeaderOnly.getIndependentVariableNames().get(i-1);
		}
		for(String celltype : celltypes){
			header += "\teffectDirectionDosage2_"+celltype;
		}
		//header += "\tgenotypeConfiguration";
		//for(String celltype : cellCounts.getAllCelltypes()){
		//	header += "\tgenotypeConfiguration_"+celltype;
		//}\

		if(commandLineOptions.getWholeBloodQTL()){
			header += "\tSpearman correlation expression~GT\tSpearman correlation p-value";
		}

		//header += "\tStandardError";
		List<String> output = new ArrayList<String>();
		output.add(header);
		for(DeconvolutionResult deconvolutionResult : deconvolutionResults){
			InteractionModelCollection interactionModelCollection = deconvolutionResult.getInteractionModelCollection();

			String results = "";
			results += deconvolutionResult.getQtlName()+"\t"+Utils.listToTabSeparatedString(deconvolutionResult.getPvalues());
			InteractionModel bestFullModel = null;

			bestFullModel = interactionModelCollection.getBestFullModel();


			results += "\t"+Utils.listToTabSeparatedString(bestFullModel.getEstimateRegressionParameters());

			// check what the genotype configuration is and the beta of the interaction term. 
			// If genotype configuration == 0 and beta == positive, dosage2 effect = positive
			// If genotype configuration == 1 and beta == negative, dosage2 effect = positive
			// else is negative
			int numberOfCelltypes = cellCounts.getNumberOfCelltypes();
			for(int i = 0; i < numberOfCelltypes; ++i){
				char genotypeConfiguration = 0;
				double estimatedRegressionParameter;


				estimatedRegressionParameter = bestFullModel.getEstimateRegressionParameters()[i+numberOfCelltypes];
				genotypeConfiguration = bestFullModel.getGenotypeConfiguration().charAt(i);

				if (genotypeConfiguration == '0'){
					// add cellCounts.getNumberOfCelltypes() to get the regression parameter for the interaction term (first ones are indepent effect betas)
					if(estimatedRegressionParameter < 0){
						results += "\t-";			
					}
					else{
						results += "\t+";
					}
				}else if(genotypeConfiguration == '1'){
					if(estimatedRegressionParameter < 0){
						results += "\t+";			
					}
					else{
						results += "\t-";
					}
				}
				else{
					throw new RuntimeException(String.format("Genotype configuration should be 0 or 1, not %s", genotypeConfiguration));
				}

			}

			//results += "\t"+bestFullModel.getGenotypeConfiguration();
			//for(String celltype : cellCounts.getAllCelltypes()){
			//	InteractionModel bestCtModel = deconvolutionResult.getInteractionModelCollection().getBestCtModel(celltype); 
			//	results += "\t"+bestCtModel.getGenotypeConfiguration();
			//}
			if(commandLineOptions.getWholeBloodQTL()){
				results += "\t"+deconvolutionResult.getWholeBloodQTL();
				results += "\t"+deconvolutionResult.getWholeBloodQTLpvalue();
			}

			//results += "\t"+bestFullModel.getEstimatedStandardError();
			output.add(results);	
		}

		Path file = Paths.get(outputFolder+"/"+commandLineOptions.getOutfile());
		Files.write(file, output, Charset.forName("UTF-8"));

		Boolean writePredictedExpression = commandLineOptions.getOutputPredictedExpression(); 
		if(writePredictedExpression){
			writePredictedExpression(deconvolutionResults);
		}
		DeconvolutionLogger.log.info(String.format("Deconvolution output written to %s", file.toAbsolutePath()));
		DeconvolutionLogger.log.info(String.format("Files with additional info in  %s", outputFolder));
	}

	/*
	 * Write the predicted expression values to a separate file
	 * 
	 * @param deconvolutionResult The deconvolutionresult
	 */
	private void writePredictedExpression(List<DeconvolutionResult> deconvolutionResults) throws IOException, IllegalAccessException{
		DeconvolutionResult deconResult = deconvolutionResults.get(0);
		String header = "";
		for(String sampleName : deconResult.getInteractionModelCollection().getSampleNames()){
			// counts.get(0) is the sample name
			header += "\t"+sampleName;

		}

		List<String> output = new ArrayList<String>();
		output.add(header);

		for(DeconvolutionResult deconvolutionResult : deconvolutionResults){

			String results = "";
			InteractionModel bestFullModel = deconvolutionResult.getInteractionModelCollection().getBestFullModel();
			results += deconvolutionResult.getQtlName()+"\t"+Utils.listToTabSeparatedString(bestFullModel.getPredictedValues());
			output.add(results);	
		}



		Path file = Paths.get(outputFolder+"predictedExpressionLevels.txt");
		Files.write(file, output, Charset.forName("UTF-8"));
		DeconvolutionLogger.log.info(String.format("predicted expression written to %s", file.toAbsolutePath()));
	}

	
	/**
	 * Compare and return the p-value of two linear models being
	 * significantly different
	 *
	 * From Joris Meys: http://stackoverflow.com/a/35458157/651779 1.
	 * calculate MSE for the largest model by dividing the Residual Sum of
	 * Squares (RSS) by the degrees of freedom (df) 2. calculate the
	 * MSEdifference by substracting the RSS of both models (result is
	 * "Sum of Sq." in the R table), substracting the df for both models
	 * (result is "Df" in the R table), and divide these numbers. 3. Divide
	 * 2 by 1 and you have the F value 4. calculate the p-value using the F
	 * value in 3 and for df the df-difference in the numerator and df of
	 * the largest model in the denominator. For more info:
	 * http://www.bodowinter.com/tutorial/bw_anova_general.pdf
	 * 
	 * @param sumOfSquaresModelA A vector with the genotype of all samples
	 * for *one* eQTL-gene pair
	 * 
	 * @param sumOfSquaresModelB A vector with the expression levels of all
	 * samples for *one* eQTL-gene pair
	 * 
	 * @param degreesOfFreedomA A 2D list with for all samples the different
	 * cell counts
	 * 
	 * @param degreesOfFreedomB A 2D list with for all samples the different
	 * cell counts
	 * 
	 * @param no_intercept	If intercept was removed to calculate the sum of squares
	 * 
	 * @return The p-value result from comparing two linear models with the
	 * the Anova test
	 */
	public double anova(double sumOfSquaresModelA, double sumOfSquaresModelB, int degreesOfFreedomA,
			int degreesOfFreedomB, Boolean no_intercept) {
		if (no_intercept) {
			// removing the intercept will give another degree of freedom
			++degreesOfFreedomA;
			++degreesOfFreedomB;
		}
		// Within-group Variance
		double meanSquareError = sumOfSquaresModelA / degreesOfFreedomA;

		int degreesOfFreedomDifference = Math.abs(degreesOfFreedomB - degreesOfFreedomA);
		// Between-group Variance
		// 234111286.801326
		double meanSquareErrorDiff = Math.abs((sumOfSquaresModelA - sumOfSquaresModelB) / (degreesOfFreedomDifference));

		/**
		 * F = Between-group Variance / Within-group Variance <- high value if
		 * variance between the models is high, and variance within the models
		 * is low
		 **/
		if(meanSquareError == 0){
			throw new RuntimeException("meanSquareError should not be 0");
		}
		double Fval = meanSquareErrorDiff / meanSquareError;
		/***
		 * Make an F distribution with degrees of freedom as parameter. If full
		 * model and ctModel have the same number of samples, difference in df
		 * is 1 and degreesOfFreedomB are all the terms of the ctModel (so neut%
		 * + eos% + ... + neut% * GT + eos% * GT With 4 cell types and 1891
		 * samples the dfs are 1883 and 1884, giving the below distribution
		 * http://keisan.casio.com/exec/system/1180573186
		 **/
		FDistribution Fdist = new FDistribution(degreesOfFreedomDifference, degreesOfFreedomB);
		/*** Calculate 1 - the probability of observing a lower Fvalue **/
		double pval = 1 - Fdist.cumulative(Fval);
		return pval;
	}

	public DeconvolutionResult deconvolution(Qtl qtl) throws RuntimeException, IllegalAccessException, IOException {
		return deconvolution(qtl.getExpressionVector(), qtl.getGenotypeVector(), qtl.getQtlName());
	}

	/**
	 * Make the linear regression models and then do an Anova of the sum of
	 * squares
	 * 
	 * Full model: Exp ~ celltype_1 + celltype_2 + ... + celltype_n +
	 * celltype_1:Gt + celltype_2:Gt + ... + celltype_n:Gt <- without
	 * intercept
	 * 
	 * Compare with anova to Exp ~ celltype_1 + celltype_2 + celtype_n +
	 * celltype_1:Gt + celltype_2:Gt + .. + celltype_n-1 <- without
	 * intercept Exp ~ celltype_1 + celltype_2 + celtype_n + celltype_1:Gt +
	 * .. + celltype_n <- without intercept Exp ~ celltype_1 + celltype_2 +
	 * celtype_n + celltype_2:Gt + .. + celltype_n <- without intercept
	 *
	 * 
	 * @param expression A vector with the expression value per sample
	 * 
	 * @param genotypes A vector with the expression levels of all
	 * samples for *one* eQTL-gene pair. This should include qtl names as in first column, and sample names in first row
	 * 
	 * @param qtlName Name of the QTL (usaully snp name + gene name)
	 * 
	 * @return A list with for each celltype a p-value for the celltype
	 * specific eQTL for one eQTL
	 */
	private DeconvolutionResult deconvolution(double[] expression, double[] genotypes, String qtlName) throws RuntimeException, IllegalAccessException, 
																											  IOException {


		/** 
		 * If roundDosage option is selected on the command line, round of the dosage to closest integer -> 0.49 = 0, 0.51 = 1, 1.51 = 2. 
		 * If minimumSamplesPerGenotype is selected on the command line, check for current QTL if for each dosage (in case they are not round
		 * the dosages are binned in same way as with roundDosage option) there are at least <minimumSamplesPerGenotype> samples that have it.
		 */

		if (commandLineOptions.getRoundDosage()) {
			for (int i = 0; i < genotypes.length; ++i) {
				if (commandLineOptions.getRoundDosage()){
					genotypes[i] = Math.round(genotypes[i]);
				}
			}
		}

		InteractionModelCollection interactionModelCollection = new InteractionModelCollection(cellCounts, 
				commandLineOptions.getGenotypeConfigurationType());
		interactionModelCollection.setQtlName(qtlName);
		interactionModelCollection.setGenotypes(genotypes);
		interactionModelCollection.setExpressionValues(expression);

		/**
		 * For each cell type model, e.g. ctModel 1 -> y = neut% + mono% + neut%:GT; ctModel 2 -> y = neut% + mono% + mono%:GT, one for each cell type, 
		 * where the interaction term (e.g mono%:GT) of the celltype:genotype to test is removed, calculate and save the observations in an observation vector
		 * where the observation vector for the example ctModel 1 is
		 *  
		 * 		celltypeModel = [[sample1_neut%, sample1_mono%, sample1_neut%*sample1_genotype], [sample2_neut%, sample2_mono%, sample2_neut%*sample2_genotype]]
		 *  
		 * with for each sample a cellcount percentage for each cell type and the genotype of the QTL that is being testetd. 
		 * 
		 * Using this observation vector calculate the sum of squares and test with Anova if it is significantly different from the sum of squares of the full model. 
		 * Here the full model includes all interaction terms of the cell type models, e.g. fullModel -> y = neut% + mono% + neut%:GT + mono%:GT so the observation vector
		 * 
		 * 		fullModel = [[sample1_neut%, sample1_mono%, sample1_neut%*sample1_genotype, sample1_mono%*sample1_genotype], [sample2_neut%, ..., etc]]
		 * 
		 */
		interactionModelCollection.createObservedValueMatricesFullModel();
		interactionModelCollection.findBestFullModel();		
		interactionModelCollection.createObservedValueMatricesCtModels();
		interactionModelCollection.findBestCtModel();
		calculateDeconvolutionPvalue(interactionModelCollection);

		double wholeBloodQTL = 0;
		double wholeBloodQTLpvalue = 0;
		if(commandLineOptions.getWholeBloodQTL()){
			// if true calculate spearman correlation between genotypes and expression values (i.e. whole blood eQTL)
			wholeBloodQTL = new SpearmansCorrelation().correlation(interactionModelCollection.getGenotypes(), interactionModelCollection.getExpessionValues());
			wholeBloodQTLpvalue = Statistics.calculateSpearmanTwoTailedPvalue(wholeBloodQTL, cellCounts.getNumberOfSamples());
		}
		DeconvolutionResult deconResult =  new DeconvolutionResult();

		interactionModelCollection.cleanUp(!commandLineOptions.getOutputPredictedExpression());
		deconResult = new DeconvolutionResult(interactionModelCollection, wholeBloodQTL, wholeBloodQTLpvalue);
		return deconResult;
	}


	/**
	 * get pvalue for each ctmodel
	 * 
	 * @param interactionModelCollection InteractionModelCollection object that has fullModel and ctModels for ANOVA comparison
	 */
	private void calculateDeconvolutionPvalue(InteractionModelCollection interactionModelCollection) 
			throws IllegalAccessException, IOException {
		for (int modelIndex = 0; modelIndex < cellCounts.getNumberOfCelltypes(); ++modelIndex) {
			String celltypeName = cellCounts.getCelltype(modelIndex);
			InteractionModel fullModel;

			fullModel = interactionModelCollection.getBestFullModel();

			int expressionLength = interactionModelCollection.getExpessionValues().length;
			if (expressionLength != fullModel.getModelLength()) {
				throw new RuntimeException("expression vector and fullModel have different number of samples.\nexpression: "
						+ expressionLength + "\nfullModel: " + fullModel.getModelLength());
			}

			InteractionModel ctModel = interactionModelCollection.getBestCtModel(celltypeName);
			double pval = anova(fullModel.getSumOfSquares(), ctModel.getSumOfSquares(), 
					fullModel.getDegreesOfFreedom(),ctModel.getDegreesOfFreedom(), 
					true);

			ctModel.setPvalue(pval);
			interactionModelCollection.setPvalue(pval, ctModel.getCelltypeName());
			// TODO: why is this method called twice?
			interactionModelCollection.setPvalue(pval,interactionModelCollection
					.getBestCtModel(cellCounts.getCelltype(modelIndex)).getModelName());

		}

	}
}