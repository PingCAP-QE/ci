interface SimpleProwOwners {
  options?: { no_parent_owners?: boolean };
  labels?: string[];
  approvers?: string[];
  emeritus_approvers?: string[];
  reviewers?: string[];
}

interface FilterProwOwners {
  options?: { no_parent_owners?: boolean };
  filters: { [pattern: string]: SimpleProwOwners };
}

type ProwOwners = SimpleProwOwners | FilterProwOwners;

type ProwOwnersAliases = Record<string, string[]>;

interface ProwRepoOwners {
  owners: Record<string, ProwOwners>;
  aliases: ProwOwnersAliases;
}

/*
 * merge two ProwOwners objects
 *
 * @param a - The first ProwOwners object to merge.
 * @param b - The second ProwOwners object to merge.
 * @returns A new ProwOwners object that is the result of merging a and b.
 */
function mergeProwOwners(a: ProwOwners, b: ProwOwners): ProwOwners {
  // Helper function to check if an object is FilterProwOwners
  const isFilterProwOwners = (obj: ProwOwners): obj is FilterProwOwners =>
    "filters" in obj;

  // Helper function to merge simple owners
  const mergeSimpleOwners = (
    a: SimpleProwOwners,
    b: SimpleProwOwners,
  ): SimpleProwOwners => {
    const result = {
      options: { ...a.options, ...b.options },
      labels: b.labels !== undefined ? b.labels : a.labels,
      approvers: b.approvers !== undefined ? b.approvers : a.approvers,
      emeritus_approvers: [
        ...new Set([
          ...(a.emeritus_approvers || []),
          ...(b.emeritus_approvers || []),
        ]),
      ].sort(),
      reviewers: b.reviewers !== undefined ? b.reviewers : a.reviewers,
    } as SimpleProwOwners;

    // omit the zero fileds.
    if (!result.options || Object.keys(result.options).length === 0) {
      delete result.options;
    }
    if (!result.labels || result.labels.length === 0) {
      delete result.labels;
    }

    if (!result.approvers || result.approvers.length === 0) {
      delete result.approvers;
    }
    if (!result.emeritus_approvers || result.emeritus_approvers.length === 0) {
      delete result.emeritus_approvers;
    }
    if (!result.reviewers || result.reviewers.length === 0) {
      delete result.reviewers;
    }

    return result;
  };

  // If both are filter owners
  if (isFilterProwOwners(a) && isFilterProwOwners(b)) {
    const result: FilterProwOwners = {
      options: { ...a.options, ...b.options },
      filters: { ...a.filters },
    };

    // Merge filters
    for (const pattern in b.filters) {
      if (pattern in result.filters) {
        result.filters[pattern] = mergeSimpleOwners(
          result.filters[pattern],
          b.filters[pattern],
        );
      } else {
        result.filters[pattern] = b.filters[pattern];
      }
    }

    // omit the zero fileds.
    if (!result.options || Object.keys(result.options).length === 0) {
      delete result.options;
    }

    return result;
  }

  // If both are simple owners
  if (!isFilterProwOwners(a) && !isFilterProwOwners(b)) {
    return mergeSimpleOwners(a, b);
  }

  // If one is filter and one is simple, convert simple to filter and merge
  if (isFilterProwOwners(a) && !isFilterProwOwners(b)) {
    return mergeProwOwners(a, { filters: { ".*": b } } as FilterProwOwners);
  } else {
    return mergeProwOwners({ filters: { ".*": a } } as FilterProwOwners, b);
  }
}

/*
 * merge two ProwOwnersAliases objects
 *
 * @param a - The first ProwOwnersAliases object to merge.
 * @param b - The second ProwOwnersAliases object to merge.
 * @returns A new ProwOwnersAliases object that is the result of merging a and b.
 */
function mergeProwOwnersAliases(
  a: ProwOwnersAliases,
  b: ProwOwnersAliases,
): ProwOwnersAliases {
  const result: ProwOwnersAliases = { ...a };
  for (const key in b) {
    result[key] = b[key];
  }
  return result;
}

function mergeProwRepoOwners(
  a: ProwRepoOwners,
  b: ProwRepoOwners,
): ProwRepoOwners {
  const result: ProwRepoOwners = {
    aliases: mergeProwOwnersAliases(a.aliases, b.aliases),
    owners: {},
  };
  for (const key in a.owners) {
    result.owners[key] = mergeProwOwners(a.owners[key], b.owners[key] || {});
  }
  for (const key in b.owners) {
    if (!(key in result.owners)) {
      result.owners[key] = b.owners[key];
    }
  }

  return result;
}

function sameObjects(a: object, b: object) {
  return orderJSONStringify(a) === orderJSONStringify(b);
}

function orderJSONStringify(owners: object) {
  const sortedArray = Object.entries(owners).sort(([keyA], [keyB]) =>
    keyA.localeCompare(keyB)
  );
  const sortedDictionary = Object.fromEntries(sortedArray);
  return JSON.stringify(sortedDictionary);
}

export { mergeProwRepoOwners, sameObjects };
export type {
  FilterProwOwners,
  ProwOwners,
  ProwOwnersAliases,
  ProwRepoOwners,
  SimpleProwOwners,
};
