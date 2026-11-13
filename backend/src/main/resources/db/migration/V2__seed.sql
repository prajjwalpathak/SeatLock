-- V2__seed.sql: demo catalog data. 3 organizers, 4 venues + seat layouts, 30 events,
-- 90 shows (3 per event, one week apart, 19:30 IST), per-section prices.
-- Show dates are relative to the migration date so the demo always has upcoming shows.
-- Organizer accounts are locked: '!' is not a valid BCrypt hash. Demo logins arrive on W2.

-- =========================== organizers ===========================
INSERT INTO users (email, password_hash, display_name, role, auth_provider) VALUES
                                                                                ('organizer.live@seatlock.test',   '!', 'Skyline Live Entertainment',  'ORGANIZER', 'LOCAL'),
                                                                                ('organizer.cinema@seatlock.test', '!', 'Reelhouse Pictures',          'ORGANIZER', 'LOCAL'),
                                                                                ('organizer.laughs@seatlock.test', '!', 'Chuckle Factory Productions', 'ORGANIZER', 'LOCAL');

-- =========================== venues ===========================
INSERT INTO venues (name, city, address) VALUES
                                             ('Aurora Arena',    'Delhi',     '12 Ring Road, Lajpat Nagar, New Delhi 110024'),
                                             ('Monsoon Theatre', 'Mumbai',    '45 Marine Drive, Churchgate, Mumbai 400020'),
                                             ('Banyan Hall',     'Bengaluru', '88 MG Road, Ashok Nagar, Bengaluru 560001'),
                                             ('Riverside Stage', 'Pune',      '7 Koregaon Park Road, Pune 411001');

-- =========================== seat layouts ===========================
-- Rows A, B, C... from the front. Each row has one aisle gap in the middle (grid_col skips
-- one column); the two seats beside the gap are aisle seats.
-- Sections by row: first 3 rows PLATINUM, next 4 GOLD, the rest SILVER.
INSERT INTO seats (venue_id, section, row_label, seat_number, grid_row, grid_col, is_aisle)
SELECT v.id,
       CASE WHEN r < 3 THEN 'PLATINUM' WHEN r < 7 THEN 'GOLD' ELSE 'SILVER' END,
       chr(65 + r),
       n,
       r,
       CASE WHEN n <= cfg.seat_count / 2 THEN n - 1 ELSE n END,
       n IN (cfg.seat_count / 2, cfg.seat_count / 2 + 1)
FROM (VALUES ('Aurora Arena',    12, 24),
             ('Monsoon Theatre', 10, 20),
             ('Banyan Hall',     14, 28),
             ('Riverside Stage',  8, 16)) AS cfg (venue_name, row_count, seat_count)
         JOIN venues v ON v.name = cfg.venue_name
         CROSS JOIN LATERAL generate_series(0, cfg.row_count - 1) AS r
CROSS JOIN LATERAL generate_series(1, cfg.seat_count) AS n;

-- =========================== events + shows ===========================
-- Venue for event #seq is venue[seq % 4]; show k (0..2) is on day seq + 7k at 14:00 UTC.
-- Two shows at the same venue would need seq1 - seq2 = 7 or 14 AND a multiple of 4: impossible,
-- so uq_shows_venue_start can never be violated.
WITH cfg (seq, organizer_email, title, category, language, duration, status, description) AS (
    VALUES
        (1, 'organizer.cinema@seatlock.test', 'The Last Monsoon', 'MOVIE', 'Hindi', 142, 'PUBLISHED',
         'A family drama set in a Konkan fishing village. Three estranged siblings reunite during a record-breaking monsoon to save the boat-building workshop their late father left behind. Warm, tearful and full of coastal music.'),
        (2, 'organizer.cinema@seatlock.test', 'Orbit of Silence', 'MOVIE', 'English', 128, 'PUBLISHED',
         'A tense space thriller about the last two crew members of a research station orbiting Saturn, who lose contact with Earth and must decide whom to trust. Best experienced on the big screen with full surround sound.'),
        (3, 'organizer.cinema@seatlock.test', 'Chai & Chaos', 'MOVIE', 'Hindi', 118, 'PUBLISHED',
         'A feel-good comedy about a roadside tea stall in Old Delhi that accidentally goes viral overnight. Expect wedding crashers, a stubborn food critic and a lot of masala chai. Suitable for the whole family.'),
        (4, 'organizer.cinema@seatlock.test', 'Kingdom of Kites', 'MOVIE', 'Hindi', 135, 'PUBLISHED',
         'A colourful coming-of-age story set during the Uttarayan kite festival in Ahmedabad. A shy teenager trains with a retired champion to win the rooftop kite battle and his first real friendship.'),
        (5, 'organizer.cinema@seatlock.test', 'Neon Bazaar', 'MOVIE', 'English', 121, 'PUBLISHED',
         'A slick heist thriller set in a Mumbai night market, where a disgraced locksmith assembles an unlikely crew for one last job. Sharp dialogue, rain-soaked chases and a twist you will not see coming.'),
        (6, 'organizer.cinema@seatlock.test', 'Little Lanterns', 'MOVIE', 'Tamil', 102, 'PUBLISHED',
         'An animated family adventure about a group of fireflies who must light the way home for a lost elephant calf in the Nilgiri hills. Gentle humour and songs make it ideal for young children.'),
        (7, 'organizer.cinema@seatlock.test', 'Paper Boats', 'MOVIE', 'Bengali', 110, 'PUBLISHED',
         'A tender Kolkata story of two childhood friends who meet again after twenty years at the tram depot where they once raced paper boats in the rain. Quiet, poetic and beautifully shot.'),
        (8, 'organizer.cinema@seatlock.test', 'The Ninth Signal', 'MOVIE', 'Telugu', 150, 'PUBLISHED',
         'A high-octane action film about a railway signal engineer who uncovers a smuggling ring along the Hyderabad to Vizag line. Packed with stunts, a thumping background score and a train-top finale.'),
        (9, 'organizer.live@seatlock.test', 'Sitar and Synth: An Evening of Fusion', 'CONCERT', 'Instrumental', 120, 'PUBLISHED',
         'A live fusion concert pairing classical sitar with analogue synthesizers, tabla and electronic beats. A seated, immersive evening with a light show designed around the music.'),
        (10, 'organizer.live@seatlock.test', 'Bollywood Retro Night', 'CONCERT', 'Hindi', 150, 'PUBLISHED',
         'A full live band and four singers perform golden-era Bollywood hits from the sixties to the nineties. Sing along to the classics and dance in the aisles during the final medley.'),
        (11, 'organizer.live@seatlock.test', 'Indie Echoes Live', 'CONCERT', 'English', 135, 'PUBLISHED',
         'Three rising independent bands share one stage for a night of guitar-driven indie rock and dream pop. High energy up front, relaxed seating at the back.'),
        (12, 'organizer.live@seatlock.test', 'Sufi Under the Stars', 'CONCERT', 'Urdu', 140, 'PUBLISHED',
         'An open-hearted evening of qawwali and Sufi poetry performed by a twelve-member ensemble. Soaring vocals, harmonium and clapping rhythms that build late into the night.'),
        (13, 'organizer.live@seatlock.test', 'Carnatic Morning Raga Series', 'CONCERT', 'Tamil', 110, 'PUBLISHED',
         'A calm recital of Carnatic classics on veena, violin and mridangam. Short explanations between pieces make it welcoming for first-time listeners.'),
        (14, 'organizer.live@seatlock.test', 'Bass Drop Arena Tour', 'CONCERT', 'English', 180, 'PUBLISHED',
         'A high-energy electronic dance music night with three DJs, lasers and a pyrotechnic finale. Loud, bright and built for dancing from start to finish.'),
        (15, 'organizer.live@seatlock.test', 'Unplugged Acoustic Sessions', 'CONCERT', 'Hindi', 100, 'PUBLISHED',
         'Intimate acoustic versions of popular Hindi songs performed by a singer-songwriter trio with guitar, cajon and flute. A cosy, seated show perfect for a date night.'),
        (16, 'organizer.laughs@seatlock.test', 'Startup Struggles: Stand-Up Special', 'COMEDY', 'English', 75, 'PUBLISHED',
         'A stand-up special about pitch decks, burnt-out founders and investors who reply in emojis. Sharp observational comedy for anyone who has ever worked in tech.'),
        (17, 'organizer.laughs@seatlock.test', 'Desi Parents Decoded', 'COMEDY', 'Hindi', 80, 'PUBLISHED',
         'A comedian explains, with charts and impressions, exactly what Indian parents mean when they say we will see. Relatable family humour in Hindi and English.'),
        (18, 'organizer.laughs@seatlock.test', 'Open Mic Mania', 'COMEDY', 'English', 90, 'PUBLISHED',
         'Ten new comedians, five minutes each, and one host holding it all together. Raw, unpredictable and often the funniest night of the month.'),
        (19, 'organizer.laughs@seatlock.test', 'Traffic Jam Diaries', 'COMEDY', 'Hindi', 70, 'PUBLISHED',
         'A one-man show built from stories collected in Bengaluru, Delhi and Mumbai traffic. Auto drivers, honking philosophers and navigation apps all get roasted.'),
        (20, 'organizer.laughs@seatlock.test', 'Improv Night: Make It Up', 'COMEDY', 'English', 90, 'PUBLISHED',
         'A fully improvised comedy show where the audience supplies the scenes, characters and plot twists. No two nights are ever the same.'),
        (21, 'organizer.laughs@seatlock.test', 'Tamil Tickles Live', 'COMEDY', 'Tamil', 75, 'PUBLISHED',
         'A Tamil stand-up evening about Chennai summers, filter coffee debates and cinema-obsessed uncles. Fast-paced and family friendly.'),
        (22, 'organizer.live@seatlock.test', 'Echoes of Hampi', 'THEATRE', 'English', 130, 'PUBLISHED',
         'A historical play set in the final days of the Vijayanagara empire, staged with live percussion and shadow puppetry. A sweeping story of loyalty, art and survival.'),
        (23, 'organizer.live@seatlock.test', 'Dastangoi: Tales Retold', 'THEATRE', 'Urdu', 95, 'PUBLISHED',
         'The revived art of Urdu storytelling, performed by two dastangos in traditional white attire. Ancient adventures, magic and wit brought alive with only voice and gesture.'),
        (24, 'organizer.live@seatlock.test', 'The Glass Courtyard', 'THEATRE', 'Hindi', 115, 'PUBLISHED',
         'A gripping chamber drama about four neighbours in an old Lucknow haveli whose secrets surface during one long power cut. Minimal set, powerful performances.'),
        (25, 'organizer.live@seatlock.test', 'Ghar Waapsi: A Marathi Natak', 'THEATRE', 'Marathi', 140, 'PUBLISHED',
         'A warm Marathi family play about a son who returns from abroad to find his parents have turned the family home into a bustling guest house. Funny, emotional and very Pune.'),
        (26, 'organizer.live@seatlock.test', 'City Kabaddi Showdown', 'SPORTS', 'Hindi', 120, 'PUBLISHED',
         'Four top city clubs battle it out in a fast, physical kabaddi tournament with live commentary. Great views from every section of the stands.'),
        (27, 'organizer.live@seatlock.test', 'Esports Finals: Valor Cup', 'SPORTS', 'English', 240, 'PUBLISHED',
         'The grand finals of a national tactical shooter championship, played live on stage with giant screens and casters. Bring your loudest cheers for the final map.'),
        (28, 'organizer.live@seatlock.test', 'Weekend Pottery for Beginners', 'WORKSHOP', 'English', 180, 'PUBLISHED',
         'A hands-on beginner pottery workshop covering wedging, centring and throwing simple bowls on the wheel. All clay, tools and firing included, no experience needed.'),
        (29, 'organizer.live@seatlock.test', 'Street Photography Walk and Talk', 'WORKSHOP', 'English', 150, 'DRAFT',
         'A guided photo walk through old city lanes followed by a feedback session on composition, light and storytelling. Bring any camera or a smartphone.'),
        (30, 'organizer.laughs@seatlock.test', 'Stand-Up Comedy Writing Workshop', 'WORKSHOP', 'English', 120, 'PUBLISHED',
         'Learn how professional comedians find premises, write punchlines and test material at open mics. Ends with a short performance for those who want to try.')
),
     new_events AS (
INSERT INTO events (organizer_id, title, description, category, language, duration_minutes, status)
SELECT u.id, c.title, c.description, c.category, c.language, c.duration, c.status
FROM cfg c
         JOIN users u ON u.email = c.organizer_email
    RETURNING id, title
)
INSERT INTO shows (event_id, venue_id, starts_at)
SELECT ne.id,
       v.id,
       ((current_date + c.seq + 7 * k) + time '14:00') AT TIME ZONE 'UTC'   -- 19:30 IST
FROM new_events ne
         JOIN cfg c ON c.title = ne.title
         JOIN venues v
              ON v.name = (ARRAY['Aurora Arena', 'Monsoon Theatre', 'Banyan Hall', 'Riverside Stage'])[c.seq % 4 + 1]
CROSS JOIN generate_series(0, 2) AS k;

-- =========================== prices ===========================
-- One show_seat per seat per show. Base price by category; PLATINUM 2x, GOLD 1.5x, SILVER 1x.
INSERT INTO show_seats (show_id, seat_id, price_paise)
SELECT sh.id,
       st.id,
       CASE st.section
           WHEN 'PLATINUM' THEN p.base_paise * 2
           WHEN 'GOLD'     THEN p.base_paise * 3 / 2
           ELSE p.base_paise
           END
FROM shows sh
         JOIN events ev ON ev.id = sh.event_id
         JOIN seats st ON st.venue_id = sh.venue_id
         JOIN (VALUES ('MOVIE',     25000),   -- Rs 250
                      ('CONCERT',  150000),   -- Rs 1,500
                      ('COMEDY',    60000),   -- Rs 600
                      ('THEATRE',   80000),   -- Rs 800
                      ('SPORTS',    50000),   -- Rs 500
                      ('WORKSHOP', 120000)    -- Rs 1,200
) AS p (category, base_paise)
              ON p.category = ev.category;