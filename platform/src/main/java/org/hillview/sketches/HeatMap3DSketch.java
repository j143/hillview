/*
 * Copyright (c) 2017 VMware Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.hillview.sketches;
import org.hillview.dataset.api.ISketch;
import org.hillview.table.api.ColumnNameAndConverter;
import org.hillview.table.api.ITable;
import org.hillview.utils.Converters;

import javax.annotation.Nullable;

public class HeatMap3DSketch implements ISketch<ITable, HeatMap3D> {
    private final IBucketsDescription bucketDescD1;
    private final IBucketsDescription bucketDescD2;
    private final IBucketsDescription bucketDescD3;
    private final ColumnNameAndConverter col1;
    private final ColumnNameAndConverter col2;
    private final ColumnNameAndConverter col3;
    private final double rate;

    public HeatMap3DSketch(IBucketsDescription bucketDesc1,
                           IBucketsDescription bucketDesc2,
                           IBucketsDescription bucketDesc3,
                           ColumnNameAndConverter col1,
                           ColumnNameAndConverter col2,
                           ColumnNameAndConverter col3) {
        this(bucketDesc1, bucketDesc2, bucketDesc3, col1, col2, col3, 1.0);
    }

    public HeatMap3DSketch(IBucketsDescription bucketDesc1,
                           IBucketsDescription bucketDesc2,
                           IBucketsDescription bucketDesc3,
                           ColumnNameAndConverter col1,
                           ColumnNameAndConverter col2,
                           ColumnNameAndConverter col3,
                           double rate) {
        this.bucketDescD1 = bucketDesc1;
        this.bucketDescD2 = bucketDesc2;
        this.bucketDescD3 = bucketDesc3;
        this.col1 = col1;
        this.col2 = col2;
        this.col3 = col3;
        this.rate = rate;
    }

    @Override
    public HeatMap3D create(final ITable data) {
        HeatMap3D result = this.getZero();
        result.createHeatMap(data.getColumn(this.col1), data.getColumn(this.col2),
                data.getColumn(this.col3), data.getMembershipSet().sample(this.rate));
        return result;
    }

    @Override
    public HeatMap3D zero() {
        return new HeatMap3D(this.bucketDescD1, this.bucketDescD2, this.bucketDescD3);
    }

    @Override
    public HeatMap3D add(@Nullable final HeatMap3D left, @Nullable final HeatMap3D right) {
        return Converters.checkNull(left).union(Converters.checkNull(right));
    }
}
