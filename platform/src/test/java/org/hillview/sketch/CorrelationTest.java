package org.hillview.sketch;

import org.hillview.dataset.api.IDataSet;
import org.hillview.sketches.CorrMatrix;
import org.hillview.sketches.FullCorrelationSketch;
import org.hillview.table.api.ITable;
import org.hillview.utils.BlasConversions;
import org.hillview.utils.LinAlg;
import org.hillview.utils.TestTables;
import org.jblas.DoubleMatrix;
import org.jblas.MatrixFunctions;
import org.jblas.ranges.AllRange;
import org.jblas.util.Random;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

public class CorrelationTest {
    public CorrelationTest() {}

    @Test
    public void testCorrelation() {
        DoubleMatrix mat = new DoubleMatrix(new double[][]{{9, 8, 4, 1, 6}, {5, 8, 2, 10, 1}, {6, 4, 1, 6, 5}});
        ITable table = BlasConversions.toTable(mat);
        List<String> colNames = new ArrayList<String>(table.getSchema().getColumnNames());

        FullCorrelationSketch fcs = new FullCorrelationSketch(colNames);
        CorrMatrix cm = fcs.create(table);

        DoubleMatrix corrMatrix = new DoubleMatrix(cm.getCorrelationMatrix());
        DoubleMatrix eigenVectors = LinAlg.eigenVectors(corrMatrix, 2);
        DoubleMatrix actualCorrMatrix = new DoubleMatrix(new double[][]{
                {1        ,  0.2773501 ,  0.83862787, -0.97655363,  0.81705717},
                {0.2773501 ,  1        ,  0.75592895, -0.06401844, -0.32732684},
                {0.83862787,  0.75592895,  1        , -0.70170418,  0.37115374},
                {-0.97655363, -0.06401844, -0.70170418,  1        , -0.92201795},
                {0.81705717, -0.32732684,  0.37115374, -0.92201795,  1}
        });
        for (int i = 0; i < corrMatrix.rows; i++) {
            for (int j = 0; j < corrMatrix.columns; j++) {
                Assert.assertEquals(actualCorrMatrix.get(i, j), corrMatrix.get(i, j), 1e-6);
            }
        }
    }

    @Test
    public void testBigCorrelation() {
        Random.seed(43);
        DoubleMatrix mat = DoubleMatrix.rand(20000, 5);
        mat.muli(4.3);
        ITable bigTable = BlasConversions.toTable(mat);
        List<String> colNames = new ArrayList<String>(bigTable.getSchema().getColumnNames());
        IDataSet<ITable> dataset = TestTables.makeParallel(bigTable, 10);

        FullCorrelationSketch fcs = new FullCorrelationSketch(colNames);
        CorrMatrix cm = dataset.blockingSketch(fcs);

        // Construct the correlation matrix that we compare against by using pure JBLAS.
        DoubleMatrix cmCheck = new DoubleMatrix(colNames.size(), colNames.size());
        DoubleMatrix means = mat.columnMeans();
        DoubleMatrix sigmas = MatrixFunctions.sqrt(
                mat.subRowVector(means).mul(mat.subRowVector(means)).columnMeans()
        );
        for (int i = 0; i < cmCheck.columns; i++) {
            DoubleMatrix c1 = mat.get(new AllRange(), i);
            for (int j = 0; j < cmCheck.rows; j++) {
                DoubleMatrix c2 = mat.get(new AllRange(), j);
                double corr = c1.dot(c2) / mat.rows;
                corr -= means.get(i) * means.get(j);
                corr /= sigmas.get(i) * sigmas.get(j);
                cmCheck.put(i, j, corr);
                cmCheck.put(j, i, corr);
            }
        }
        for (int i = 0; i < cm.getCorrelationMatrix().length; i++) {
            double[] row = cm.getCorrelationMatrix()[i];
            for (int j = 0; j < row.length; j++) {
                double actual = cm.getCorrelationMatrix()[i][j];
                double expected = cmCheck.get(i, j);
                Assert.assertEquals(expected, actual, 1e-5);
            }
        }
    }
}
