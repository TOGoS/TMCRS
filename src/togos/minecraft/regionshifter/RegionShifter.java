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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jnbt.CompoundTag;
import org.jnbt.IntTag;
import org.jnbt.NBTInputStream;
import org.jnbt.NBTOutputStream;
import org.jnbt.Tag;

public class RegionShifter
{
	static final Pattern R = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	
	static class ShiftJob {
		public final File inFile;
		public final File outFile;
		public final int destRX, destRZ;
		public ShiftJob( File inFile, File outFile, int destRX, int destRZ ) {
			this.inFile = inFile;
			this.outFile = outFile;
			this.destRX = destRX;
			this.destRZ = destRZ;
		}
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
				rewrittenLevelTagValues.put("xPos", new IntTag("xPos", cx + job.destRX*32));
				rewrittenLevelTagValues.put("zPos", new IntTag("zPos", cz + job.destRZ*32));
				for( Map.Entry<String,Tag> entry : levelTag.getValue().entrySet() ) {
					if( "xPos".equals(entry.getKey()) || "zPos".equals(entry.getKey()) ) {
						System.err.println(job.inFile+" "+cx+","+cz+" "+entry.getKey()+" = "+entry.getValue());
					} else {
						rewrittenLevelTagValues.put(entry.getKey(), entry.getValue());
					}
				}
				System.err.println("-> "+(cx + job.destRX*32) + ","+(cz + job.destRZ*32));
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
	
	public static void main(String[] args) {
		File inDir  = null;
		File outDir = null;
		int
			x0 = Integer.MIN_VALUE, z0 = Integer.MIN_VALUE,
			x1 = Integer.MAX_VALUE, z1 = Integer.MAX_VALUE;
		int shiftX = 0, shiftZ = 0;
		int debugLevel = 0;
		ConflictResolutionMode conflictResolutionMode = ConflictResolutionMode.ERROR;
		
		for( int i=0; i<args.length; ++i ) {
			if( "-o".equals(args[i]) ) {
				outDir = new File(args[++i]);
			} else if( "-bounds".equals(args[i]) ) {
				String[] b = args[++i].split(",");
				if( b.length != 4 ) {
					System.err.println("Error: Expected 4 comma-separated integers for bounds; got '"+args[i]+"'");
					System.exit(1);
				}
				x0 = Integer.parseInt(b[0]);
				z0 = Integer.parseInt(b[1]);
				x1 = Integer.parseInt(b[2]);
				z1 = Integer.parseInt(b[3]);
			} else if( "-shift".equals(args[i]) ) {
				String[] s = args[++i].split(",");
				if( s.length != 2 ) {
					System.err.println("Error: Expected 2 comma-separated integers for shift; got '"+args[i]+"'");
					System.exit(1);
				}
				shiftX = Integer.parseInt(s[0]);
				shiftZ = Integer.parseInt(s[1]);
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
			} else if( args[i].charAt(0) != '-' ) {
				inDir = new File(args[i]);
			} else {
				System.err.println("Error: Unrecognized argument: '"+args[i]+"'");
			}
		}
		
		if( inDir == null ) {
			System.err.println("Error: No input directory specified");
			System.exit(1);
		}
		if( outDir == null ) {
			System.err.println("Error: No output directory specified");
			System.exit(1);
		}
		
		int outfieldRegionCount = 0;
		List<ShiftJob> jobs = new ArrayList<ShiftJob>();
		
		String[] regionFiles = inDir.list();
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
				int destRX = (rx+shiftX), destRZ = (rz+shiftZ);
				File outRegionFile = new File(outDir, "r."+destRX+"."+destRZ+".mca");
				jobs.add(new ShiftJob(inRegionFile, outRegionFile, destRX, destRZ));
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
				System.err.println("Exception occured when shifting "+job.inFile+" to "+job.outFile);
				System.exit(1);
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
	}
}
