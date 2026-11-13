package com.seatlock.catalog.domain;

import com.seatlock.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A physical seat in a venue. grid_row/grid_col position it on the seat map (gaps = aisles). */
@Entity
@Table(name = "seats")
public class Seat extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(nullable = false, length = 40)
    private String section;

    @Column(name = "row_label", nullable = false, length = 5)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    @Column(name = "grid_row", nullable = false)
    private int gridRow;

    @Column(name = "grid_col", nullable = false)
    private int gridCol;

    @Column(name = "is_aisle", nullable = false)
    private boolean aisle;

    protected Seat() {
        // for JPA
    }

    public Seat(Venue venue, String section, String rowLabel, int seatNumber,
                int gridRow, int gridCol, boolean aisle) {
        this.venue = venue;
        this.section = section;
        this.rowLabel = rowLabel;
        this.seatNumber = seatNumber;
        this.gridRow = gridRow;
        this.gridCol = gridCol;
        this.aisle = aisle;
    }

    public Venue getVenue() { return venue; }
    public String getSection() { return section; }
    public String getRowLabel() { return rowLabel; }
    public int getSeatNumber() { return seatNumber; }
    public int getGridRow() { return gridRow; }
    public int getGridCol() { return gridCol; }
    public boolean isAisle() { return aisle; }
}