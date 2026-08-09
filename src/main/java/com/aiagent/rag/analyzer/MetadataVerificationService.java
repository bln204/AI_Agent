package com.aiagent.rag.analyzer;

import com.aiagent.repository.DepartmentRepository;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.ProjectRepository;
import com.aiagent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class MetadataVerificationService {

    private final ProjectRepository projectRepository;
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;

    public List<DetectedEntity> verifyAndResolve(Set<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<CandidateMatch> allMatches = new ArrayList<>();

        for (String candidate : candidates) {
            allMatches.addAll(collectMatches(candidate));
        }

        if (allMatches.isEmpty()) {
            return Collections.emptyList();
        }

        log.info("[ENTITY-RESOLUTION] Collected Matches: {}", 
            allMatches.stream().map(m -> m.getType() + "(" + m.getValue() + ") score=" + m.getScore()).collect(Collectors.toList()));

        return resolve(allMatches);
    }

    private List<CandidateMatch> collectMatches(String candidate) {
        List<CandidateMatch> matches = new ArrayList<>();
        String val = candidate.replaceAll("[.!?,]$", "").trim();
        if (val.length() < 3) return matches;

        // Verify PROJECT
        if (projectRepository.existsByCode(val)) {
            matches.add(new CandidateMatch(val, EntityType.PROJECT, 1.8, "ProjectRepository(Code)"));
        } else if (projectRepository.existsByName(val)) {
            matches.add(new CandidateMatch(val, EntityType.PROJECT, 1.7, "ProjectRepository(Name)"));
        }

        // Verify DEPARTMENT
        if (departmentRepository.findByCode(val).isPresent()) {
            matches.add(new CandidateMatch(val, EntityType.DEPARTMENT, 1.6, "DepartmentRepository(Code)"));
        } else if (departmentRepository.findByName(val).isPresent()) {
            matches.add(new CandidateMatch(val, EntityType.DEPARTMENT, 1.5, "DepartmentRepository(Name)"));
        }

        // Verify DOCUMENT_TITLE
        if (documentRepository.existsByTitle(val)) {
            matches.add(new CandidateMatch(val, EntityType.DOCUMENT_TITLE, 1.3, "DocumentRepository(Title)"));
        }

        // Verify CONTRACT (Decision Number)
        if (documentRepository.existsByDecisionNumber(val)) {
            matches.add(new CandidateMatch(val, EntityType.CONTRACT, 1.2, "DocumentRepository(DecisionNumber)"));
        }

        // Verify EMPLOYEE
        if (userRepository.existsByUsername(val)) {
            matches.add(new CandidateMatch(val, EntityType.EMPLOYEE, 1.1, "UserRepository(Username)"));
        }

        for (CandidateMatch match : matches) {
            log.info("[CANDIDATE-VERIFY] Candidate: '{}' -> Source: {}, Match: {}, Score: {}", 
                val, match.getSource(), match.getType(), match.getScore());
        }

        return matches;
    }

    private List<DetectedEntity> resolve(List<CandidateMatch> matches) {
        // Step 1: Resolve conflicts for the same candidate string (highest score wins)
        Map<String, CandidateMatch> bestForValue = new HashMap<>();
        for (CandidateMatch match : matches) {
            CandidateMatch current = bestForValue.get(match.getValue());
            if (current == null || match.getScore() > current.getScore()) {
                bestForValue.put(match.getValue(), match);
            }
        }

        // Step 2: Handle substring overlaps (longer string wins)
        List<CandidateMatch> sortedMatches = new ArrayList<>(bestForValue.values());
        sortedMatches.sort((a, b) -> b.getValue().length() - a.getValue().length());

        List<DetectedEntity> finalResults = new ArrayList<>();
        for (CandidateMatch candidate : sortedMatches) {
            boolean isContained = false;
            for (DetectedEntity result : finalResults) {
                if (result.getValue().contains(candidate.getValue()) && !result.getValue().equals(candidate.getValue())) {
                    isContained = true;
                    break;
                }
            }
            if (!isContained) {
                finalResults.add(new DetectedEntity(candidate.getType(), candidate.getValue()));
                log.info("[ENTITY-RESOLUTION] Selected Entity: {} ('{}') | Rule: Highest Score & Longest Match", 
                    candidate.getType(), candidate.getValue());
            } else {
                log.info("[ENTITY-RESOLUTION] Rejected Entity: {} ('{}') | Rule: Substring of existing entity", 
                    candidate.getType(), candidate.getValue());
            }
        }

        return finalResults;
    }
}
