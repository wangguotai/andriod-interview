import shark.*;
import java.io.File;
import java.util.*;

/**
 * 独立基准：用 shark 的 StreamingHprofReader 逐条读真实的 heap dump，
 * 统计 **每类实例数 / 每类字节数**，输出成 CSV，用来和自研解析器逐类对拍。
 *
 * 关键点：readRecords 的第二个参数是 OnHprofRecordTagListener（单个抽象方法 → lambda），
 * 它拿到的是 HprofRecordReader，在 lambda 里**立刻把该子记录读掉**。
 */
public class Oracle {
  public static void main(String[] a) throws Exception {
    File f = new File(a[0]);
    HprofHeader header = HprofHeader.Companion.parseHeaderOf(f);
    System.err.println("idSize=" + header.getIdentifierByteSize()
        + " recordsPosition=" + header.getRecordsPosition());

    Set<HprofRecordTag> tags = EnumSet.of(
        HprofRecordTag.STRING_IN_UTF8,
        HprofRecordTag.LOAD_CLASS,
        HprofRecordTag.CLASS_DUMP,
        HprofRecordTag.INSTANCE_DUMP,
        HprofRecordTag.OBJECT_ARRAY_DUMP,
        HprofRecordTag.PRIMITIVE_ARRAY_DUMP);

    Map<Long, String> strings = new HashMap<>();
    Map<Long, Long> classIdToNameId = new HashMap<>();
    Map<String, long[]> stats = new TreeMap<>();   // name -> [count, bytes]

    StreamingHprofReader reader = StreamingHprofReader.Companion.readerFor(f, header);
    long total = reader.readRecords(tags, (tag, length, r) -> {
      switch (tag) {
        case STRING_IN_UTF8: {
          HprofRecord.StringRecord s = r.readStringRecord(length);
          strings.put(s.getId(), s.getString());
          break;
        }
        case LOAD_CLASS: {
          HprofRecord.LoadClassRecord c = r.readLoadClassRecord();
          classIdToNameId.put(c.getId(), c.getClassNameStringId());
          break;
        }
        case CLASS_DUMP: {
          r.readClassDumpRecord();
          break;
        }
        case INSTANCE_DUMP: {
          HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord i = r.readInstanceDumpRecord();
          String n = nameOf(classIdToNameId, strings, i.getClassId());
          long bytes = i.getFieldValues().length;
          add(stats, n, bytes);
          break;
        }
        case OBJECT_ARRAY_DUMP: {
          HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord o = r.readObjectArrayDumpRecord();
          String n = nameOf(classIdToNameId, strings, o.getArrayClassId());
          long bytes = (long) o.getElementIds().length * header.getIdentifierByteSize();
          add(stats, n, bytes);
          break;
        }
        case PRIMITIVE_ARRAY_DUMP: {
          HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord p = r.readPrimitiveArrayDumpRecord();
          String n = p.getClass().getSimpleName();
          long bytes;
          if (p instanceof HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump) {
            bytes = ((HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump) p).getArray().length;
            n = "boolean[]";
          } else if (p instanceof HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump) {
            bytes = ((HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump) p).getArray().length * 2L;
            n = "char[]";
          } else if (p instanceof HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump) {
            bytes = ((HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump) p).getArray().length;
            n = "byte[]";
          } else if (p instanceof HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump) {
            bytes = ((HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump) p).getArray().length * 2L;
            n = "short[]";
          } else if (p instanceof HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump) {
            bytes = ((HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump) p).getArray().length * 4L;
            n = "int[]";
          } else if (p instanceof HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump) {
            bytes = ((HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump) p).getArray().length * 8L;
            n = "long[]";
          } else if (p instanceof HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump) {
            bytes = ((HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump) p).getArray().length * 4L;
            n = "float[]";
          } else if (p instanceof HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump) {
            bytes = ((HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump) p).getArray().length * 8L;
            n = "double[]";
          } else {
            bytes = p.getSize();
          }
          add(stats, n, bytes);
          break;
        }
        default: break;
      }
    });
    System.err.println("bytesRead=" + total + " fileLen=" + f.length());

    // CSV: name,count,bytes
    for (Map.Entry<String, long[]> e : stats.entrySet()) {
      System.out.println(e.getKey() + "," + e.getValue()[0] + "," + e.getValue()[1]);
    }
  }

  static void add(Map<String, long[]> m, String k, long bytes) {
    long[] v = m.computeIfAbsent(k, x -> new long[2]);
    v[0]++; v[1] += bytes;
  }

  // 把 hprof 的斜杠形式数组类名归一化成可读形式（与自研解析器同一口径）
  static String nameOf(Map<Long, Long> classIdToNameId, Map<Long, String> strings, long classId) {
    Long nid = classIdToNameId.get(classId);
    if (nid == null) return "<unknown>";
    String raw = strings.get(nid);
    if (raw == null) return "<unknown>";
    if (raw.length() >= 2 && raw.charAt(0) == '[') {
      char t = raw.charAt(1);
      if (t == 'L' && raw.endsWith(";")) {
        return raw.substring(2, raw.length() - 1).replace('/', '.') + "[]";
      }
      switch (t) {
        case 'B': return "byte[]";
        case 'I': return "int[]";
        case 'J': return "long[]";
        case 'C': return "char[]";
        case 'S': return "short[]";
        case 'F': return "float[]";
        case 'D': return "double[]";
        case 'Z': return "boolean[]";
        default: return raw;
      }
    }
    return raw;
  }
}
