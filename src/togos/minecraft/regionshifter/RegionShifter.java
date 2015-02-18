package togos.minecraft.regionshifter;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jnbt.CompoundTag;
import org.jnbt.DoubleTag;
import org.jnbt.IntTag;
import org.jnbt.ListTag;
import org.jnbt.LongTag;
import org.jnbt.NBTInputStream;
import org.jnbt.NBTOutputStream;
import org.jnbt.Tag;

public class RegionShifter
{
	static final Pattern R = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	
	static class ShiftJob {
		public final File inFile;
		public final File outFile;
		public final long shiftX, shiftZ;
		public final boolean generateNewUuids;
		public ShiftJob( File inFile, File outFile, long shiftX, long shiftZ, boolean generateNewUuids ) {
			if( ((shiftX | shiftZ) & 0x1FF) != 0 ) {
				throw new RuntimeException("Shift amount must be a multiple of 512; given "+shiftX+","+shiftZ); 
			}
			this.inFile = inFile;
			this.outFile = outFile;
			this.shiftX = shiftX;
			this.shiftZ = shiftZ;
			this.generateNewUuids = generateNewUuids;
		}
		public int getShiftCX() { return (int)(shiftX >> 4); }
		public int getShiftCZ() { return (int)(shiftZ >> 4); }
		public int getShiftRX() { return (int)(shiftX >> 9); }
		public int getShiftRZ() { return (int)(shiftZ >> 9); }
	}
	
	// Add entries from 'from' into 'into' for keys not already existing in 'into'
	protected static <X,Y> void merge( Map<X,Y> from, Map<X,Y> into ) {
		for( Map.Entry<X,Y> e : from.entrySet() ) {
			if( !into.containsKey(e.getKey()) ) {
				into.put(e.getKey(), e.getValue());
			}
		}
	}
	
	protected static ListTag<DoubleTag> rewriteEntityPosition(ListTag<DoubleTag> oldPos, long shiftX, long shiftY, long shiftZ) {
		ArrayList<DoubleTag> newPos = new ArrayList<DoubleTag>();
		newPos.add(new DoubleTag(oldPos.getValue().get(0).getName(), oldPos.getValue().get(0).getValue() + shiftX));
		newPos.add(new DoubleTag(oldPos.getValue().get(1).getName(), oldPos.getValue().get(1).getValue() + shiftY));
		newPos.add(new DoubleTag(oldPos.getValue().get(2).getName(), oldPos.getValue().get(2).getValue() + shiftZ));
		return new ListTag<DoubleTag>("Pos", DoubleTag.class, newPos);
	}
	
	protected static ListTag<CompoundTag> rewriteEntities(ListTag<CompoundTag> oldEntities, long shiftX, long shiftY, long shiftZ, boolean generateNewUuid) {
		List<CompoundTag> newEntities = new ArrayList<CompoundTag>();
		for( CompoundTag entity : oldEntities.getValue() ) {
			Map<String,Tag> newValues = new LinkedHashMap<String,Tag>();
			@SuppressWarnings("unchecked")
			ListTag<DoubleTag> oldPos = (ListTag<DoubleTag>)entity.getValue().get("Pos");
			newValues.put("Pos", rewriteEntityPosition(oldPos, shiftX, shiftY, shiftZ));
			if( generateNewUuid ) {
				UUID newUuid = UUID.randomUUID();
				newValues.put("UUIDMost" , new LongTag("UUIDMost" , newUuid.getMostSignificantBits() ));
				newValues.put("UUIDLeast", new LongTag("UUIDLeast", newUuid.getLeastSignificantBits()));
			}
			merge( entity.getValue(), newValues );
			newEntities.add(new CompoundTag(entity.getName(), newValues));
		}
		return new ListTag<CompoundTag>("Entities", CompoundTag.class, newEntities);
	}
	
	protected static void shift(ShiftJob job) throws IOException {
		File outDir = job.outFile.getParentFile();
		if( outDir != null && !outDir.exists() ) outDir.mkdirs();
		
		try(
			RegionFile inRegionFile = new RegionFile(job.inFile);
			RegionFile outRegionFile = new RegionFile(job.outFile)
		) {
			for( int cz=0; cz<32; ++cz ) for( int cx=0; cx<32; ++cx ) {
				if( !inRegionFile.hasChunk(cx, cz) ) continue;
				
				// Load!
				
				CompoundTag rootTag;
				try( DataInputStream dis = inRegionFile.getChunkDataInputStream(cx, cz) ) {
					if( dis == null ) continue;
					try( NBTInputStream nis = new NBTInputStream(dis) ) {
						rootTag = (CompoundTag)nis.readTag();
					}
				}
				
				CompoundTag levelTag = (CompoundTag)rootTag.getValue().get("Level");
				
				// Transform!
				
				Map<String, Tag> rewrittenLevelTagValues = new LinkedHashMap<String,Tag>();
				int oldXPos = ((IntTag)levelTag.getValue().get("xPos")).getValue();
				int oldZPos = ((IntTag)levelTag.getValue().get("zPos")).getValue();
				
				@SuppressWarnings("unchecked")
				ListTag<CompoundTag> oldEntities = (ListTag<CompoundTag>)levelTag.getValue().get("Entities");
				rewrittenLevelTagValues.put("Entities", rewriteEntities(oldEntities, job.shiftX, 0, job.shiftZ, false));
				rewrittenLevelTagValues.put("xPos", new IntTag("xPos", oldXPos + job.getShiftCX()));
				rewrittenLevelTagValues.put("zPos", new IntTag("zPos", oldZPos + job.getShiftCZ()));
				merge( levelTag.getValue(), rewrittenLevelTagValues );
				
				CompoundTag rewrittenLevelTag = new CompoundTag("Level", rewrittenLevelTagValues);
				HashMap<String,Tag> rewrittenRootTagValues = new HashMap<String,Tag>();
				rewrittenRootTagValues.put("Level",rewrittenLevelTag);
				CompoundTag rewrittenRootTag = new CompoundTag("", rewrittenRootTagValues);
				
				// Save!
				
				try( DataOutputStream dos = outRegionFile.getChunkDataOutputStream(cx, cz) ) {
					try( NBTOutputStream nos = new NBTOutputStream(dos) ) {
						nos.writeTag(rewrittenRootTag);
					}
				}
			}
		}
	}
	
	enum ConflictResolutionMode {
		KEEP,
		CLOBBER,
		ERROR
	};
	
	protected static final String USAGE =
		"Usage: tmcrs [options] -o <output-region-directory> <input-region-directory>\n" +
		"Options:\n"+
		"  -o <dir>       ; specify output region directory\n" +
		"  -shift <x>,<y> ; amount to shift input by (in region widths)\n" +
		"  -bounds <x0>,<y0>,<x1>,<y1> ; limit input regions shifted to this rectangle,\n" +
		"                 ; specified in corner coordinates (so \"0,0,1,1\" limits\n" +
		"                 ; to the region 'r.0.0.mca')\n" +
		"  -keep          ; keep existing terrain\n" +
		"  -clobber       ; overwrite existing terrain\n" +
		"  -error-on-conflict ; abort if any new terrain would overwrite existing\n" +
		"  -v             ; talk a bit\n" +
		"  -vv            ; talk more\n" +
		"  -keep-entity-uuids ; Do not regenerate entity UUIDs";
		
	protected static int dieUsageError(String message) {
		System.err.println("Error: "+message+"\n"+"Run with -? for usage information");
		return 1;
	}
	protected static int dieError(String message) {
		return dieUsageError(message);
	}
		
	protected static int _main(String[] args) {
		File inDir  = null;
		File outDir = null;
		int
			x0 = Integer.MIN_VALUE, z0 = Integer.MIN_VALUE,
			x1 = Integer.MAX_VALUE, z1 = Integer.MAX_VALUE;
		int shiftRX = 0, shiftRZ = 0;
		int debugLevel = 0;
		ConflictResolutionMode conflictResolutionMode = ConflictResolutionMode.ERROR;
		boolean generateNewUuids = true;
		
		for( int i=0; i<args.length; ++i ) {
			if( "-o".equals(args[i]) ) {
				outDir = new File(args[++i]);
			} else if( "-bounds".equals(args[i]) ) {
				String[] b = args[++i].split(",");
				if( b.length != 4 ) {
					return dieUsageError("Expected 4 comma-separated integers for bounds; got '"+args[i]+"'");
				}
				x0 = Integer.parseInt(b[0]);
				z0 = Integer.parseInt(b[1]);
				x1 = Integer.parseInt(b[2]);
				z1 = Integer.parseInt(b[3]);
			} else if( "-shift".equals(args[i]) ) {
				String[] s = args[++i].split(",");
				if( s.length != 2 ) {
					return dieUsageError("Expected 2 comma-separated integers for shift; got '"+args[i]+"'");
				}
				shiftRX = Integer.parseInt(s[0]);
				shiftRZ = Integer.parseInt(s[1]);
			} else if( "-keep".equals(args[i]) ) {
				conflictResolutionMode = ConflictResolutionMode.KEEP;
			} else if( "-clobber".equals(args[i]) ) {
				conflictResolutionMode = ConflictResolutionMode.CLOBBER;
			} else if( "-error-on-conflict".equals(args[i]) ) {
				conflictResolutionMode = ConflictResolutionMode.ERROR;
			} else if( "-v".equals(args[i]) ) {
				debugLevel = 1;
			} else if( "-vv".equals(args[i]) ) {
				debugLevel = 2;
			} else if( "-keep-entity-uuids".equals(args[i]) ) {
				generateNewUuids = false;
			} else if( "-?".equals(args[i]) || "-h".equals(args[i]) || "--help".equals(args[i]) ) {
				System.out.println(USAGE);
				return 0;
			} else if( args[i].charAt(0) != '-' ) {
				if( inDir != null ) {
					return dieUsageError("More than one input directory specified: '"+inDir+"', '"+args[i]+"'");
				}
				inDir = new File(args[i]);
			} else {
				return dieUsageError("Unrecognized argument: '"+args[i]+"'");
			}
		}
		
		if( inDir == null ) {
			return dieUsageError("No input directory specified");
		}
		if( outDir == null ) {
			return dieUsageError("No output directory specified");
		}
		if( !inDir.exists() ) {
			return dieUsageError("Input directory '"+inDir+"' does not exist.");
		}
		if( !inDir.isDirectory() ) {
			return dieUsageError("Input '"+inDir+"' is not a directory.");
		}
		
		long shiftX = (long)shiftRX * 512;
		long shiftZ = (long)shiftRZ * 512;
		
		int outfieldRegionCount = 0;
		List<ShiftJob> jobs = new ArrayList<ShiftJob>();
		
		String[] regionFiles = inDir.list();
		if( regionFiles == null ) {
			return dieError("Failed to load directory listing from '"+inDir+"'.");
		}
		Matcher m;
		for( String r : regionFiles ) {
			if( (m = R.matcher(r)).matches() ) {
				int rx = Integer.parseInt(m.group(1));
				int rz = Integer.parseInt(m.group(2));
				if( rx < x0 || rx >= x1 || rz < z0 || rz >= z1 ) {
					if( debugLevel >= 2 ) {
						System.err.println("Ignoring "+r+" (outside limit)");
					}
					++outfieldRegionCount;
					continue;
				}
				File inRegionFile = new File(inDir, r);
				int destRX = (rx+shiftRX), destRZ = (rz+shiftRZ);
				File outRegionFile = new File(outDir, "r."+destRX+"."+destRZ+".mca");
				jobs.add(new ShiftJob(inRegionFile, outRegionFile, shiftX, shiftZ, generateNewUuids));
			}
		}
		
		// Validate!
		
		boolean abort = false;
		
		// Check for conflicts!
		if( conflictResolutionMode == ConflictResolutionMode.ERROR ) {
			for( ShiftJob job : jobs ) {
				if( job.outFile.exists() ) {
					System.err.println("Error: "+job.outFile+" already exists");
					abort = true;
				}
			}
		}
		
		if( abort ) System.exit(1);
		
		int shiftedRegionCount = 0;
		int preexistingRegionCount = 0;
		for( ShiftJob job : jobs ) {
			if( job.outFile.exists() ) {
				if( conflictResolutionMode == ConflictResolutionMode.KEEP ) {
					++preexistingRegionCount;
					if( debugLevel >= 2 ) {
						System.err.println(job.outFile+" already exists; keeping existing version");
					}
					continue;
				}
			}
			
			try {
				shift(job);
			} catch( IOException e ) {
				e.printStackTrace();
				dieError("Exception occured when shifting "+job.inFile+" to "+job.outFile);
			}
			
			if( debugLevel >= 1 ) {
				System.err.println("Shifted "+job.inFile+" to "+job.outFile);
			}
			++shiftedRegionCount;
		}
		
		if( debugLevel >= 1 ) {
			System.err.println(jobs.size()+" input regions found");
			System.err.println("Shifted "+shiftedRegionCount+" regions");
			System.err.println(outfieldRegionCount+" regions ignored due to being outside bounds");
			System.err.println(preexistingRegionCount+" regions ignored because they already existed in the output directory");
		}
		
		return 0;
	}
	
	public static void main(String[] args) {
		System.exit(_main(args));
	}
}
