package com.example.lab05.service;

import com.example.lab05.dto.DashboardResponse;
import com.example.lab05.model.cassandra.SensorReading;
import com.example.lab05.model.elastic.ProductDocument;
import com.example.lab05.model.mongo.PurchaseReceipt;
import com.example.lab05.repository.mongo.PurchaseReceiptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final PurchaseReceiptRepository receiptRepository;
    private final SocialGraphService socialGraphService;
    private final SensorService sensorService;
    private final ProductSearchService searchService;
    private final RedisTemplate<String, Object> redisTemplate;

    public DashboardService(PurchaseReceiptRepository receiptRepository,
                            SocialGraphService socialGraphService,
                            SensorService sensorService,
                            ProductSearchService searchService,
                            RedisTemplate<String, Object> redisTemplate) {
        this.receiptRepository = receiptRepository;
        this.socialGraphService = socialGraphService;
        this.sensorService = sensorService;
        this.searchService = searchService;
        this.redisTemplate = redisTemplate;
    }

    public DashboardResponse getDashboard(String personName) {

        // Step 0 — Redis (check cache)
        String cacheKey = "dashboard:" + personName;
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof DashboardResponse cachedDashboard) {
                return new DashboardResponse(
                        cachedDashboard.personName(),
                        cachedDashboard.totalSpent(),
                        cachedDashboard.purchaseCount(),
                        cachedDashboard.recentPurchases(),
                        cachedDashboard.friendRecommendations(),
                        cachedDashboard.friendsOfFriends(),
                        cachedDashboard.recentActivity(),
                        cachedDashboard.youMightAlsoLike(),
                        true
                );
            }
        } catch (Exception e) {
            log.warn("Redis cache check failed for {}: {}", personName, e.getMessage());
        }

        // Step 1 — MongoDB
        List<PurchaseReceipt> allReceipts = receiptRepository.findByPersonName(personName);
        double totalSpent = allReceipts.stream().mapToDouble(PurchaseReceipt::getTotalPrice).sum();
        int purchaseCount = allReceipts.size();
        List<PurchaseReceipt> recentPurchases = allReceipts.stream()
                .sorted(Comparator.comparing(PurchaseReceipt::getPurchasedAt).reversed())
                .limit(5)
                .collect(Collectors.toList());

        // Step 2 — Neo4j (soft dependency)
        List<Map<String, Object>> friendRecommendations = List.of();
        List<String> friendsOfFriends = List.of();
        try {
            friendRecommendations = socialGraphService.getRecommendations(personName, 5);
            friendsOfFriends = socialGraphService.getFriendsOfFriends(personName).stream()
                    .map(p -> p.getName())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to fetch Neo4j data for {}: {}", personName, e.getMessage());
        }

        // Step 3 — Cassandra (soft dependency)
        List<SensorReading> recentActivity = List.of();
        try {
            recentActivity = sensorService.getLatestReadings(
                    "user-activity-" + personName.toLowerCase(), 10);
        } catch (Exception e) {
            log.warn("Failed to fetch activity for {}: {}", personName, e.getMessage());
        }

        // Step 4 — Elasticsearch (soft dependency)
        List<String> youMightAlsoLike = List.of();
        try {
            Set<String> alreadyBought = allReceipts.stream()
                    .map(PurchaseReceipt::getProductName)
                    .collect(Collectors.toSet());
            Set<String> categories = allReceipts.stream()
                    .map(PurchaseReceipt::getProductCategory)
                    .collect(Collectors.toSet());
            List<String> suggestions = new ArrayList<>();
            for (String category : categories) {
                searchService.getByCategory(category).stream()
                        .filter(p -> !alreadyBought.contains(p.getName()))
                        .limit(2)
                        .map(ProductDocument::getName)
                        .forEach(suggestions::add);
            }
            youMightAlsoLike = suggestions;
        } catch (Exception e) {
            log.warn("Failed to fetch ES suggestions for {}: {}", personName, e.getMessage());
        }

        // Step 5 — Construct and cache
        DashboardResponse dashboard = new DashboardResponse(
                personName,
                totalSpent,
                purchaseCount,
                recentPurchases,
                friendRecommendations,
                friendsOfFriends,
                recentActivity,
                youMightAlsoLike,
                false
        );

        try {
            redisTemplate.opsForValue().set(cacheKey, dashboard, Duration.ofMinutes(5));
        } catch (Exception e) {
            log.warn("Failed to cache dashboard for {}: {}", personName, e.getMessage());
        }

        return dashboard;
    }
}
