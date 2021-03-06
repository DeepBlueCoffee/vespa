// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/vespalib/stllike/string.h>
#include <vespa/fastos/timestamp.h>

namespace search {

class FileKit
{
private:
    static bool _syncFiles;
public:
    static bool createStamp(const vespalib::stringref &name);
    static bool hasStamp(const vespalib::stringref &name);
    static bool removeStamp(const vespalib::stringref &name);

    /**
     * Returns the modification time of the given file/directory,
     * or time stamp 0 if stating of file/directory fails.
     **/
    static fastos::TimeStamp getModificationTime(const vespalib::stringref &name);
};

}
