//! Mutual servers & mutual friends.
//!
//! Everything is computed from data the client already holds, so opening a
//! profile costs no extra network round-trips. Cost is O(servers + friends).

use crate::model::{Id, Server, ServerRef, User, UserRef};
use serde::Serialize;
use std::collections::{HashMap, HashSet};

#[derive(Serialize, Debug)]
pub struct Mutuals {
    pub servers: Vec<ServerRef>,
    pub friends: Vec<UserRef>,
}

pub fn compute(
    me: Id,
    other: Id,
    servers: &[Server],
    friends_of: &HashMap<Id, Vec<Id>>,
    users: &HashMap<Id, User>,
) -> Mutuals {
    let servers = servers
        .iter()
        .filter(|s| s.member_ids.contains(&me) && s.member_ids.contains(&other))
        .map(ServerRef::from)
        .collect();

    let empty = Vec::new();
    let mine: HashSet<Id> = friends_of.get(&me).unwrap_or(&empty).iter().copied().collect();
    let mut friends: Vec<UserRef> = friends_of
        .get(&other)
        .unwrap_or(&empty)
        .iter()
        .filter(|id| **id != me && **id != other && mine.contains(id))
        .filter_map(|id| users.get(id))
        .map(UserRef::from)
        .collect();
    friends.sort_by(|a, b| a.display_name.to_lowercase().cmp(&b.display_name.to_lowercase()));

    Mutuals { servers, friends }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Status;

    fn user(id: Id, name: &str) -> User {
        User {
            id,
            name: name.into(),
            display_name: name.into(),
            hue: 0,
            status: Status::Online,
            platforms: vec![],
            bio: String::new(),
            pronouns: String::new(),
            friend_since: None,
            avatar_url: None,
            banner_url: None,
            status_text: String::new(),
                bot: false,
        }
    }

    fn server(id: Id, members: &[Id]) -> Server {
        Server {
            id,
            name: format!("s{id}"),
            hue: 0,
            shape: 0,
            member_ids: members.to_vec(),
            channels: vec![],
            mentions: 0,
            icon_url: None,
                banner_url: None,
        }
    }

    #[test]
    fn finds_shared_servers_and_friends() {
        let servers = vec![server(1, &[1, 2, 3]), server(2, &[1, 3]), server(3, &[2, 3])];
        let friends_of = HashMap::from([(1, vec![2, 3, 4]), (3, vec![1, 2, 4, 5])]);
        let users: HashMap<Id, User> =
            [(2, "bea"), (4, "ash"), (5, "cy")].into_iter().map(|(i, n)| (i, user(i, n))).collect();

        let m = compute(1, 3, &servers, &friends_of, &users);
        assert_eq!(m.servers.iter().map(|s| s.id).collect::<Vec<_>>(), vec![1, 2]);
        assert_eq!(m.friends.iter().map(|u| u.id).collect::<Vec<_>>(), vec![4, 2]);
    }
}
