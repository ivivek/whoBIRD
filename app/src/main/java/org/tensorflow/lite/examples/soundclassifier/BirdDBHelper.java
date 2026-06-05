package org.tensorflow.lite.examples.soundclassifier;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.*;

public class BirdDBHelper extends SQLiteOpenHelper {

    // Database name and table columns
    private static final String DB_NAME = "BirdDatabase.db";
    private static final int DATABASE_VERSION = 3;
    public static final String TABLE_NAME = "BirdObservations";
    private static final String COLUMN_ID = "ID";
    private static final String COLUMN_MILLIS = "TimeInMillis";
    private static final String COLUMN_LATITUDE = "Latitude";
    private static final String COLUMN_LONGITUDE = "Longitude";
    private static final String COLUMN_NAME = "SpeciesName";
    private static final String COLUMN_SPECIES_ID = "BirdNET_ID";
    private static final String COLUMN_PROBABILITY = "Probability";
    private static final String COLUMN_SYNCED = "Synced";
    private static final String COLUMN_CLIP_SYNCED = "ClipSynced";
    private static BirdDBHelper instance = null;
    
    public BirdDBHelper(Context context) {
        super(context, DB_NAME, null, DATABASE_VERSION);
    }
    
    @Override
    public void onCreate(SQLiteDatabase db) {
        // Create the table for bird observations with all columns and their data types.
        String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS "+TABLE_NAME+" (" +
                COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COLUMN_MILLIS + " LONG," +
                COLUMN_LATITUDE + " FLOAT," +
                COLUMN_LONGITUDE + " FLOAT," +
                COLUMN_NAME + " TEXT," +
                COLUMN_SPECIES_ID + " INTEGER," +
                COLUMN_PROBABILITY + " FLOAT," +
                COLUMN_SYNCED + " INTEGER NOT NULL DEFAULT 0," +
                COLUMN_CLIP_SYNCED + " INTEGER NOT NULL DEFAULT 0);";
        db.execSQL(CREATE_TABLE);
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_"+TABLE_NAME+"_synced ON "+TABLE_NAME+"("+COLUMN_SYNCED+");");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 -> v2: additive column. Preserve existing detection history.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE "+TABLE_NAME+" ADD COLUMN "+COLUMN_SYNCED+" INTEGER NOT NULL DEFAULT 0;");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_"+TABLE_NAME+"_synced ON "+TABLE_NAME+"("+COLUMN_SYNCED+");");
        }
        // v2 -> v3: track per-row WAV clip upload (0 = pending, 1 = uploaded or no clip on disk).
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE "+TABLE_NAME+" ADD COLUMN "+COLUMN_CLIP_SYNCED+" INTEGER NOT NULL DEFAULT 0;");
        }
    }
    
    public synchronized void addEntry(String name, float latitude, float longitude, int speciesId, float probability, long timeInMillis) {
        // Insert a new row into the table with all columns and their values from parameters.
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COLUMN_NAME, name);
        cv.put(COLUMN_MILLIS, timeInMillis);
        cv.put(COLUMN_LATITUDE, latitude);
        cv.put(COLUMN_LONGITUDE, longitude);
        cv.put(COLUMN_SPECIES_ID, speciesId);
        cv.put(COLUMN_PROBABILITY, probability);
        
        db.insert(TABLE_NAME, null, cv); // Insert the row into the table with all columns and their values from parameters.
    }
    
    public synchronized void clearAllEntries() {
        SQLiteDatabase db = getWritableDatabase();
        String CLEAR_TABLE = "DELETE FROM "+ TABLE_NAME;
        
        db.execSQL(CLEAR_TABLE); // Delete all rows in the table, effectively clearing it out.
    }
    
    public synchronized List<String> exportAllEntriesAsCSV() {
        SQLiteDatabase db = getReadableDatabase();
        String SELECT_ALL = "SELECT * FROM "+ TABLE_NAME;
        
        Cursor cursor = db.rawQuery(SELECT_ALL, null); // Execute the query to select all rows from the table and store them in a cursor object for further processing.
        
        List<String> csvDataList = new ArrayList<>(); // Create an empty list of strings that will hold each row's data as CSV formatted string.
        
        if (cursor != null && cursor.moveToFirst()) { 
            do {
                long millis = cursor.getLong(1);        // time in milliseconds
                float latitude = cursor.getFloat(2);    // latitude
                float longitude = cursor.getFloat(3);   // longitude
                String nameStr = cursor.getString(4);   // name of the bird species
                int speciesId = cursor.getInt(5);       // id for the species in BirdNET
                float probability = cursor.getFloat(6); // estimated probability that this observation is correct
                
                String csvString = millis + "," + latitude + "," + longitude + "," + nameStr + "," + speciesId + "," + probability;

                csvDataList.add(csvString);    
            } while (cursor.moveToNext());
            cursor.close();
        }
        return csvDataList;
    }

    public synchronized List<BirdObservation> getAllBirdObservations(boolean detailed) {
        SQLiteDatabase db = this.getReadableDatabase();

        String SELECT_ALL = "SELECT * FROM "+ TABLE_NAME;
        Cursor cursor = db.rawQuery(SELECT_ALL, null); // Execute the query to select all rows from the table and store them in a cursor object for further processing.

        List<BirdObservation> birdObservations = new ArrayList<>();
        BirdObservation previousEntry = null;
        if (cursor.moveToFirst()) {
            do {
                BirdObservation birdObservation = new BirdObservation();
                birdObservation.setId(cursor.getInt(0));
                birdObservation.setMillis(cursor.getLong(1));
                birdObservation.setLatitude(cursor.getFloat(2));
                birdObservation.setLongitude(cursor.getFloat(3));
                birdObservation.setName(cursor.getString(4));
                birdObservation.setSpeciesId(cursor.getInt(5));
                birdObservation.setProbability(cursor.getFloat(6));

                if (!detailed) {
                    // Check if the current entry has the same species id as previousEntry and a higher probability value
                    if ((previousEntry != null && previousEntry.getSpeciesId() == birdObservation.getSpeciesId()) && birdObservation.getProbability() > previousEntry.getProbability()) {
                        // Replace the previous entry in List<BirdObservation> birdObservations with this new entry
                        birdObservations.remove(previousEntry);
                        previousEntry = birdObservation;
                        birdObservations.add(birdObservation);
                    } else if (previousEntry != null && previousEntry.getSpeciesId() == birdObservation.getSpeciesId() && birdObservation.getProbability() <= previousEntry.getProbability()) {
                        // Skip this entry as it has a lower probability value than the previous one with the same species id
                    } else {
                        // Add the current entry to the list if it doesn't match the conditions above or if there is no previous entry
                        birdObservations.add(birdObservation);
                        previousEntry = birdObservation;
                    }
                } else {
                    // If condensed is false, simply add all entries to the list without any modifications
                    birdObservations.add(birdObservation);
                }
            } while (cursor.moveToNext());
        }
        cursor.close();
        return birdObservations;
    }

    public static BirdDBHelper getInstance(Context context) {
        if (instance == null && context != null) {
            instance = new BirdDBHelper(context.getApplicationContext());
        }
        return instance;
    }

    /** Fetch up to `limit` unsynced rows, oldest first. Returns empty list if none. */
    public synchronized List<BirdObservation> getUnsyncedBatch(int limit) {
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE " + COLUMN_SYNCED + " = 0 ORDER BY " + COLUMN_ID + " ASC LIMIT " + limit;
        Cursor cursor = db.rawQuery(sql, null);
        List<BirdObservation> out = new ArrayList<>();
        if (cursor.moveToFirst()) {
            do {
                BirdObservation o = new BirdObservation();
                o.setId(cursor.getInt(0));
                o.setMillis(cursor.getLong(1));
                o.setLatitude(cursor.getFloat(2));
                o.setLongitude(cursor.getFloat(3));
                o.setName(cursor.getString(4));
                o.setSpeciesId(cursor.getInt(5));
                o.setProbability(cursor.getFloat(6));
                out.add(o);
            } while (cursor.moveToNext());
        }
        cursor.close();
        return out;
    }

    /** Mark the given row IDs as synced. */
    public synchronized void markSynced(List<Integer> ids) {
        if (ids.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
        }
        String[] args = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) args[i] = String.valueOf(ids.get(i));
        db.execSQL("UPDATE " + TABLE_NAME + " SET " + COLUMN_SYNCED + " = 1 WHERE " + COLUMN_ID + " IN (" + placeholders + ")", args);
    }

    /**
     * Rows whose metadata is synced but whose WAV clip hasn't been dealt with yet
     * (uploaded, or found absent on disk). Oldest first.
     */
    public synchronized List<BirdObservation> getClipPendingBatch(int limit) {
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT " + COLUMN_ID + ", " + COLUMN_MILLIS + " FROM " + TABLE_NAME +
                " WHERE " + COLUMN_SYNCED + " = 1 AND " + COLUMN_CLIP_SYNCED + " = 0" +
                " ORDER BY " + COLUMN_ID + " ASC LIMIT " + limit;
        Cursor cursor = db.rawQuery(sql, null);
        List<BirdObservation> out = new ArrayList<>();
        if (cursor.moveToFirst()) {
            do {
                BirdObservation o = new BirdObservation();
                o.setId(cursor.getInt(0));
                o.setMillis(cursor.getLong(1));
                out.add(o);
            } while (cursor.moveToNext());
        }
        cursor.close();
        return out;
    }

    /** Mark the given row IDs as clip-handled (uploaded or no clip exists). */
    public synchronized void markClipSynced(List<Integer> ids) {
        if (ids.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
        }
        String[] args = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) args[i] = String.valueOf(ids.get(i));
        db.execSQL("UPDATE " + TABLE_NAME + " SET " + COLUMN_CLIP_SYNCED + " = 1 WHERE " + COLUMN_ID + " IN (" + placeholders + ")", args);
    }

    public synchronized int getUnsyncedCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_NAME + " WHERE " + COLUMN_SYNCED + " = 0", null);
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }
}
