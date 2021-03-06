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

package org.hillview.table;

import net.openhft.hashing.LongHashFunction;
import org.hillview.table.api.*;
import org.hillview.utils.Converters;

import javax.annotation.Nullable;
import java.time.LocalDateTime;

/*
 * Column of dates, implemented as an array of dates and a BitSet of missing values
 */
@SuppressWarnings("EmptyMethod")
public final class DateArrayColumn
        extends DoubleArrayColumn
        implements IDateColumn, IMutableColumn {
    public DateArrayColumn(final ColumnDescription description, final int size) {
        super(description, size);
        this.checkKind(ContentsKind.Date);
    }

    public DateArrayColumn(final ColumnDescription description,
                           final LocalDateTime[] data) {
        super(description, data.length);
        this.checkKind(ContentsKind.Date);
    }

    @Nullable
    @Override
    public LocalDateTime getDate(final int rowIndex) {
        return Converters.toDate(this.getDouble(rowIndex));
    }

    @Override
    public void set(int rowIndex, @Nullable Object value) {
        this.set(rowIndex, (LocalDateTime)value);
    }

    public void set(final int rowIndex, @Nullable final LocalDateTime value) {
        if (value == null)
            this.setMissing(rowIndex);
        else
            this.set(rowIndex, Converters.toDouble(value));
    }

    @Override
    public double asDouble(int rowIndex, @Nullable IStringConverter unused) {
        return super.getDouble(rowIndex);
    }

    @Nullable
    @Override
    public String asString(int rowIndex) {
        if (this.isMissing(rowIndex))
            return null;
        return this.getDate(rowIndex).toString();
    }

    @Override
    public IndexComparator getComparator() {
        return super.getComparator();
    }

    @Override
    public long hashCode64(int rowIndex, LongHashFunction hash) {
        return super.hashCode64(rowIndex, hash);
    }

    @Override
    public IColumn convertKind(ContentsKind kind, String newColName, IMembershipSet set) {
        return IDateColumn.super.convertKind(kind, newColName, set);
    }
}
