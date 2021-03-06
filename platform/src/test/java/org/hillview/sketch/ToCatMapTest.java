package org.hillview.sketch;

import org.hillview.dataset.api.IDataSet;
import org.hillview.dataset.api.IMap;
import org.hillview.maps.ConvertColumnMap;
import org.hillview.sketches.DistinctStrings;
import org.hillview.sketches.DistinctStringsSketch;
import org.hillview.table.*;
import org.hillview.table.api.ContentsKind;
import org.hillview.table.api.IRowIterator;
import org.hillview.table.api.ITable;
import org.hillview.utils.JsonList;
import org.hillview.utils.TestTables;
import org.hillview.utils.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class ToCatMapTest {

    private Table tableWithStringColumn() {
        ColumnDescription c0 = new ColumnDescription("Name", ContentsKind.String, false);
        ColumnDescription c1 = new ColumnDescription("Age", ContentsKind.Integer, false);
        StringArrayColumn sac = new StringArrayColumn(c0,
                new String[] { "Mike", "John", "Tom", "Bill", "Bill", "Smith", "Donald", "Bruce",
                        "Bob", "Frank", "Richard", "Steve", "Dave", "Mike", "Ed" });
        IntArrayColumn iac = new IntArrayColumn(c1, new int[] { 20, 30, 10, 10, 20, 30, 20, 30, 10,
                40, 40, 20, 10, 50, 60 });
        return new Table(Arrays.asList(sac, iac));
    }

    @Test
    public void testToCatMap() {
        ITable table = this.tableWithStringColumn();
        System.out.println("Table before conversion:");
        TestUtils.printTable(table);
        IMap<ITable, ITable> map = new ConvertColumnMap("Name", "Name Categorical", ContentsKind.Category);
        ITable result = map.apply(table);
        System.out.println("Table after conversion:");
        TestUtils.printTable(result);

        Assert.assertTrue(result.getColumn("Name Categorical").getDescription().kind == ContentsKind.Category);
        IRowIterator rowIt = result.getRowIterator();
        int row = rowIt.getNextRow();
        while (row >= 0) {
            Assert.assertEquals(
                    result.getColumn("Name").getString(row),
                    result.getColumn("Name Categorical").getString(row)
            );
            row = rowIt.getNextRow();
        }
    }

    @Test
    public void testToCatMapBig() {
        ITable table = this.tableWithStringColumn();
        IDataSet<ITable> bigTable = TestTables.makeParallel(table, 3);
        IMap<ITable, ITable> map = new ConvertColumnMap("Name", "Name Categorical", ContentsKind.Category);

        IDataSet<ITable> result = bigTable.blockingMap(map);

        DistinctStringsSketch uss1 = new DistinctStringsSketch(100, new String[]{"Name"});
        DistinctStringsSketch uss2 = new DistinctStringsSketch(100, new String[]{"Name Categorical"});
        JsonList<DistinctStrings> ds1 = result.blockingSketch(uss1);
        JsonList<DistinctStrings> ds2 = result.blockingSketch(uss2);
        Set<String> strings1 = new HashSet<String>();
        for (String s : ds1.get(0).getStrings()) {
            strings1.add(s);
        }
        Set<String> strings2 = new HashSet<String>();
        for (String s : ds2.get(0).getStrings()) {
            strings2.add(s);
        }

        Assert.assertTrue(strings1.equals(strings2));
    }
}
