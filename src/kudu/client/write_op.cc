// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "kudu/client/write_op.h"

#include <ostream>
#include <type_traits>

#include <glog/logging.h>

#include "kudu/client/client.h" // IWYU pragma: keep
#include "kudu/client/schema.h"
#include "kudu/common/common.pb.h"
#include "kudu/common/row.h"
#include "kudu/common/row_operations.pb.h"
#include "kudu/common/schema.h"
#include "kudu/common/types.h"
#include "kudu/util/bitmap.h"
#include "kudu/util/slice.h"

using kudu::client::sp::shared_ptr;
using std::unique_ptr;

namespace kudu {
namespace client {

RowOperationsPB_Type ToInternalWriteType(KuduWriteOperation::Type type) {
  switch (type) {
    case KuduWriteOperation::INSERT: return RowOperationsPB_Type_INSERT;
    case KuduWriteOperation::UPDATE: return RowOperationsPB_Type_UPDATE;
    case KuduWriteOperation::DELETE: return RowOperationsPB_Type_DELETE;
    case KuduWriteOperation::UPSERT: return RowOperationsPB_Type_UPSERT;
    case KuduWriteOperation::INSERT_IGNORE: return RowOperationsPB_Type_INSERT_IGNORE;
    case KuduWriteOperation::UPDATE_IGNORE: return RowOperationsPB_Type_UPDATE_IGNORE;
    case KuduWriteOperation::DELETE_IGNORE: return RowOperationsPB_Type_DELETE_IGNORE;
    case KuduWriteOperation::UPSERT_IGNORE: return RowOperationsPB_Type_UPSERT_IGNORE;
    default: LOG(FATAL) << "Unexpected write operation type: " << type;
  }
}

// WriteOperation --------------------------------------------------------------

KuduWriteOperation::KuduWriteOperation(const shared_ptr<KuduTable>& table)
  : table_(table),
    row_(table->schema().schema_),
    size_in_buffer_(0) {}

KuduWriteOperation::~KuduWriteOperation() {}


int64_t KuduWriteOperation::SizeInBuffer() const {
  if (size_in_buffer_ > 0) {
    // Once computed, the raw size of the operation is cached and returned
    // for all subsequent calls.
    return size_in_buffer_;
  }
  const Schema* schema = row_.schema();
  int size = 1; // for the operation type

  // Add size of isset bitmap (always present).
  size += BitmapSize(schema->num_columns());
  // Add size of null bitmap (present if the schema has nullables)
  size += ContiguousRowHelper::non_null_bitmap_size(*schema);
  // The column data itself:
  for (int i = 0; i < schema->num_columns(); i++) {
    if (row_.IsColumnSet(i) && !row_.IsNull(i)) {
      size += schema->column(i).type_info()->size();
      if (schema->column(i).type_info()->physical_type() == BINARY) {
        ContiguousRow row(schema, row_.row_data_);
        const Slice* bin = reinterpret_cast<const Slice*>(row.cell_ptr(i));
        size += bin->size();
      }
    }
  }
  size_in_buffer_ = size;
  return size;
}

// Insert -----------------------------------------------------------------------

KuduInsert::KuduInsert(const shared_ptr<KuduTable>& table)
  : KuduWriteOperation(table) {
}

KuduInsert::~KuduInsert() {}

// InsertIgnore -----------------------------------------------------------------

KuduInsertIgnore::KuduInsertIgnore(const shared_ptr<KuduTable>& table)
  : KuduWriteOperation(table) {
}

KuduInsertIgnore::~KuduInsertIgnore() {}

// Update -----------------------------------------------------------------------

KuduUpdate::KuduUpdate(const shared_ptr<KuduTable>& table)
  : KuduWriteOperation(table) {
}

KuduUpdate::~KuduUpdate() {}

// UpdateIgnore -----------------------------------------------------------------

KuduUpdateIgnore::KuduUpdateIgnore(const shared_ptr<KuduTable>& table)
    : KuduWriteOperation(table) {
}

KuduUpdateIgnore::~KuduUpdateIgnore() {}

// Delete -----------------------------------------------------------------------

KuduDelete::KuduDelete(const shared_ptr<KuduTable>& table)
  : KuduWriteOperation(table) {
}

KuduDelete::~KuduDelete() {}

// DeleteIgnore -----------------------------------------------------------------

KuduDeleteIgnore::KuduDeleteIgnore(const shared_ptr<KuduTable>& table)
    : KuduWriteOperation(table) {
}

KuduDeleteIgnore::~KuduDeleteIgnore() {}

// Upsert -----------------------------------------------------------------------

KuduUpsert::KuduUpsert(const shared_ptr<KuduTable>& table)
  : KuduWriteOperation(table) {
}

KuduUpsert::~KuduUpsert() {}

// UpsertIgnore -----------------------------------------------------------------

KuduUpsertIgnore::KuduUpsertIgnore(const shared_ptr<KuduTable>& table)
    : KuduWriteOperation(table) {
}

KuduUpsertIgnore::~KuduUpsertIgnore() {}

} // namespace client
} // namespace kudu
