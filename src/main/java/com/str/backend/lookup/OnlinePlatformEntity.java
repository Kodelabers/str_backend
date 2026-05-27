package com.str.backend.lookup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(schema = "str_rn", name = "online_platform")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class OnlinePlatformEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "platform_id", nullable = false, updatable = false)
    private Long platformId;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Column(name = "url", length = 500, nullable = false)
    private String url;

    @Column(name = "listing_url", length = 500)
    private String listingUrl;

    @Column(name = "active", nullable = false)
    private boolean active;
}
