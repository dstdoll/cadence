//! Which name servers the in-process resolver is given.
//!
//! The StoreServices libraries resolve names inside this process (`ANDROID_DNS_MODE=local`), so they
//! never see Android's own DNS: not Private DNS, not a VPN's resolver, not a network change. The
//! servers they query are the ones handed to `_resolv_set_nameservers_for_net`, and this module
//! decides that list.

/// Public resolvers used when the device reports none, and appended after the network's own as a
/// last resort. Bionic asks the servers in order and only moves on when one does not answer, so these
/// see queries only when every server of the network failed to.
const PUBLIC: [&str; 2] = ["1.1.1.1", "1.0.0.1"];

/// Bionic keeps at most this many servers per network (`MAXNS`).
const MAX_SERVERS: usize = 4;

/// The servers to install for a comma-separated list from the active network (`""` when it has none).
///
/// The network's servers come first, in the order Android reports them, at most three; one public
/// resolver follows so that a server that stopped answering (a VPN that went down, an IPv6 server on
/// a network without IPv6 routing) does not leave the sign-in without any resolver at all.
pub fn nameservers(active: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for server in active.split(',').map(str::trim).filter(|s| !s.is_empty()) {
        if out.len() == MAX_SERVERS - 1 {
            break;
        }
        if !out.iter().any(|s| s == server) {
            out.push(server.to_owned());
        }
    }
    if out.is_empty() {
        return PUBLIC.iter().map(|s| (*s).to_owned()).collect();
    }
    if let Some(public) = PUBLIC.iter().find(|p| !out.iter().any(|s| s == *p)) {
        out.push((*public).to_owned());
    }
    out
}

/// A log-safe summary of [nameservers]: how many of each family, never the addresses (a carrier's or a
/// home router's DNS address says where the device is).
pub fn describe(servers: &[String]) -> String {
    let v6 = servers.iter().filter(|s| s.contains(':')).count();
    let public = servers.iter().filter(|s| PUBLIC.contains(&s.as_str())).count();
    format!(
        "{} servers (ipv4={} ipv6={} public={})",
        servers.len(),
        servers.len() - v6,
        v6,
        public
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn no_network_servers_means_the_public_pair() {
        assert_eq!(nameservers(""), vec!["1.1.1.1", "1.0.0.1"]);
        assert_eq!(nameservers(" , ,"), vec!["1.1.1.1", "1.0.0.1"]);
    }

    #[test]
    fn network_servers_first_then_one_public_fallback() {
        assert_eq!(
            nameservers("192.168.1.1,fe80::1%wlan0"),
            vec!["192.168.1.1", "fe80::1%wlan0", "1.1.1.1"]
        );
    }

    #[test]
    fn at_most_four_servers_and_no_duplicates() {
        assert_eq!(
            nameservers("10.0.0.1,10.0.0.1,10.0.0.2,10.0.0.3,10.0.0.4,10.0.0.5"),
            vec!["10.0.0.1", "10.0.0.2", "10.0.0.3", "1.1.1.1"]
        );
    }

    #[test]
    fn a_network_that_already_uses_the_public_resolver_gets_the_other_one() {
        assert_eq!(nameservers("1.1.1.1"), vec!["1.1.1.1", "1.0.0.1"]);
    }

    #[test]
    fn the_summary_never_contains_an_address() {
        let servers = nameservers("192.168.1.1,2001:db8::53");
        let text = describe(&servers);
        assert_eq!(text, "3 servers (ipv4=2 ipv6=1 public=1)");
        assert!(!text.contains("192.168"));
    }
}
