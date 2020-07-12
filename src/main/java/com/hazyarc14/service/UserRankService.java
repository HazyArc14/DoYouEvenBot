package com.hazyarc14.service;

import com.hazyarc14.enums.RANK;
import com.hazyarc14.model.UserInfo;
import com.hazyarc14.repository.UserInfoRepository;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class UserRankService {

    public static final Double MINRANK = 0.0;
    public static final Double MAXRANK = 2200.0;

    public static final Double minsPerPointEarned = 10.0;
    public static final Double serverBoosterBonus = 1.10;

    private static final List<String> guildRoleNames = Arrays.asList("Guardian", "Brave", "Heroic", "Fabled", "Mythic", "Legend");

    @Autowired
    UserInfoRepository userInfoRepository;

    public void createUserRank(Member member) {

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(member.getIdLong());
        userInfo.setUserName(member.getEffectiveName());
        userInfo.setRank(0.0);
        userInfo.setActive(false);

        userInfoRepository.save(userInfo);

    }

    public UserInfo calculateUserRank(Guild guild, Member member, UserInfo userInfo) {

        Double currentRank = userInfo.getRank();

        Timestamp joinedTm = userInfo.getJoinedChannelTm();
        Timestamp requestTmstp = new Timestamp(System.currentTimeMillis());

        Long minutesInChannel = TimeUnit.MINUTES.convert(requestTmstp.getTime() - joinedTm.getTime(), TimeUnit.MILLISECONDS);
        Double pointsToAdd = Math.floor(minutesInChannel.doubleValue() / minsPerPointEarned);
        Double remainder = Math.abs(minutesInChannel.doubleValue() - pointsToAdd * minsPerPointEarned);

        if (userInfo.getActive()) {
            Long remainderMilliseconds = TimeUnit.MILLISECONDS.convert(remainder.longValue(), TimeUnit.MINUTES);
            Timestamp newJoinedTm = new Timestamp(requestTmstp.getTime() - remainderMilliseconds);
            userInfo.setJoinedChannelTm(newJoinedTm);
        }

        List<Member> serverBoosters = guild.getBoosters();
        for (Member serverBooster: serverBoosters) {
            if (serverBooster.getIdLong() == userInfo.getUserId())
                pointsToAdd *= serverBoosterBonus;
        }

        if (member != null) {

            if (member.getVoiceState().inVoiceChannel()) {
                VoiceChannel voiceChannel = member.getVoiceState().getChannel();
                if (!voiceChannel.getMembers().isEmpty()) {
                    Integer membersInChannelCount = voiceChannel.getMembers().size();

                    if (membersInChannelCount >= 5) {
                        pointsToAdd *= 1.5;
                    }
                }
            }

        }

        Double updatedRank = currentRank + pointsToAdd;
        if (updatedRank > MAXRANK)
            updatedRank = MAXRANK;
        if (updatedRank < MINRANK)
            updatedRank = MINRANK;

        userInfo.setRank(updatedRank);

        userInfoRepository.save(userInfo);
        updateRolesByUser(guild, member, userInfo);

        return userInfo;

    }

    public void updateRolesByUser(Guild guild, Member member, UserInfo userInfo) {

        List<Role> memberRoles = member.getRoles();
        List<Role> newMemberRoles = new ArrayList<>();

        RANK currentUserRank = calculateRoleByRank(userInfo.getRank());
        List<Role> roles = guild.getRolesByName(currentUserRank.getRoleName(), false);

        if (!roles.isEmpty()) {

            Role newGuildRole = roles.get(0);

            for (int i = 0; i < memberRoles.size(); i++) {
                if (!guildRoleNames.contains(memberRoles.get(i).getName()))
                    newMemberRoles.add(memberRoles.get(i));
            }

            newMemberRoles.add(newGuildRole);
            guild.modifyMemberRoles(member, newMemberRoles).queue();

        }

    }

    public void updateAllUserRoles(Guild guild) {

        List<UserInfo> userInfoList = userInfoRepository.findAll();
        userInfoList.forEach(userRank -> {

            Member member;
            try {
                member = guild.retrieveMemberById(userRank.getUserId()).complete();
            } catch (ErrorResponseException e) {
                if (e.getErrorCode() == 10007)
                    userInfoRepository.delete(userRank);
                return;
            }

            List<Role> memberRoles = member.getRoles();
            List<Role> newMemberRoles = new ArrayList<>();

            RANK currentUserRank = calculateRoleByRank(userRank.getRank());
            Role newGuildRole = guild.getRolesByName(currentUserRank.getRoleName(), false).get(0);

            for (int i = 0; i < memberRoles.size(); i++) {
                if (!guildRoleNames.contains(memberRoles.get(i).getName()))
                    newMemberRoles.add(memberRoles.get(i));
            }

            newMemberRoles.add(newGuildRole);
            guild.modifyMemberRoles(member, newMemberRoles).queue();

        });

    }

    public void updateAllUserRanks(JDA jda) {

        Guild guild = jda.getGuildById(376520761340329984L);
        TextChannel defaultChannel = guild.getDefaultChannel();

        List<UserInfo> userInfoList = userInfoRepository.findAll();

        if (!userInfoList.isEmpty()) {

            userInfoList.forEach(userRank -> {
                if (userRank.getActive()) {

                    guild.retrieveMemberById(userRank.getUserId()).queue(member -> {
                        UserInfo updatedUserInfo = calculateUserRank(guild, member, userRank);
                    });

                }
            });

        }

    }

    public void applyDecayToUserRanks(JDA jda) {

        Guild guild = jda.getGuildById(376520761340329984L);

        List<UserInfo> userInfoList = userInfoRepository.findAll();
        Timestamp currentTm = new Timestamp(System.currentTimeMillis());

        userInfoList.forEach(userRank -> {

            Double currentRank = userRank.getRank();
            Timestamp joinedTm = userRank.getJoinedChannelTm();

            if (joinedTm != null && !userRank.getActive()) {

                Long temp = TimeUnit.DAYS.convert(currentTm.getTime() - joinedTm.getTime(), TimeUnit.MILLISECONDS);
                Integer daySinceChannelJoined = temp.intValue();

                if (daySinceChannelJoined > 7) {

                    Double pointsToRemove = calculateDecayValue(currentRank);

                    userRank.setRank(currentRank - pointsToRemove);
                    userInfoRepository.save(userRank);

                }

            }

        });

        updateAllUserRoles(guild);

    }

    public Double calculateDecayValue(Double rank) {

        if (rank < RANK.BRAVE.getValue())
            return 0.0;

        if (rank >= RANK.BRAVE.getValue() && rank < RANK.HEROIC.getValue())
            return 1.0;

        if (rank >= RANK.HEROIC.getValue() && rank < RANK.FABLED.getValue())
            return 5.0;

        if (rank >= RANK.FABLED.getValue() && rank < RANK.MYTHIC.getValue())
            return 10.0;

        if (rank >= RANK.MYTHIC.getValue() && rank < RANK.LEGEND.getValue())
            return 15.0;

        if (rank >= RANK.LEGEND.getValue())
            return 20.0;

        return 0.0;

    }

    public RANK calculateRoleByRank(Double rank) {

        if (rank < RANK.BRAVE.getValue())
            return RANK.GUARDIAN;

        if (rank >= RANK.BRAVE.getValue() && rank < RANK.HEROIC.getValue())
            return RANK.BRAVE;

        if (rank >= RANK.HEROIC.getValue() && rank < RANK.FABLED.getValue())
            return RANK.HEROIC;

        if (rank >= RANK.FABLED.getValue() && rank < RANK.MYTHIC.getValue())
            return RANK.FABLED;

        if (rank >= RANK.MYTHIC.getValue() && rank < RANK.LEGEND.getValue())
            return RANK.MYTHIC;

        if (rank >= RANK.LEGEND.getValue())
            return RANK.LEGEND;

        return null;

    }

}